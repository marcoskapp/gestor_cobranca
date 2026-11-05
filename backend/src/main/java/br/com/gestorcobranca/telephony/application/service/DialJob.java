package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.compliance.application.port.in.ContactGate;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactDecision;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.DialResult;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.HangupReason;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.OriginateRequest;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.ProviderContext;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.TransientProviderException;
import br.com.gestorcobranca.telephony.domain.model.Agent;
import br.com.gestorcobranca.telephony.domain.model.Call;
import br.com.gestorcobranca.telephony.domain.model.CallStatus;
import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;
import br.com.gestorcobranca.telephony.domain.repository.AgentRepository;
import br.com.gestorcobranca.telephony.domain.repository.CallRepository;
import br.com.gestorcobranca.telephony.domain.repository.TelephonyAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Discagem do clique para ligar ({@code telephony.dial}), no tenant da chamada, como sistema, em fases
 * (handler sem transação, decisão 852; TEL-02):
 * <ol>
 *   <li><b>preparo</b> (transação curta, chamada em {@code FOR UPDATE}): chamada que não está mais
 *       {@code QUEUED} nem aguardando o provedor → nada a fazer; conta inativa/sem adaptador ou operador sem
 *       ramal → {@code FAILED}; {@code ContactGate#authorizeAndRecord} imediatamente antes de discar: negado →
 *       {@code FAILED/CONTACT_DENIED}, operador liberado; permitido → tentativa registrada e pedido de
 *       discagem gravado ({@code QUEUED → DIALING}, sem id do provedor). Confirmado antes do HTTP: os eventos
 *       e pedidos de instrução do provedor já encontram a chamada;</li>
 *   <li><b>provedor</b> (sem transação e sem lock): {@code originate}. Falha transitória que comprovadamente
 *       não criou a chamada → a mesma chamada vai, uma única vez, para a conta de contingência da regra (se o
 *       operador tem ramal nela; o desvio é gravado em transação curta antes do novo pedido); demais falhas
 *       transitórias → exceção e o job é reagendado. A nova execução encontra o pedido sem resposta e o
 *       repete com a mesma tentativa do compliance (sem novo registro). Na última tentativa a chamada é
 *       encerrada como {@code FAILED/RETRIES_EXHAUSTED};</li>
 *   <li><b>resultado</b> (transação curta): aceite → id do provedor gravado (a chamada pode já ter avançado
 *       pelos eventos); se ela foi encerrada aqui enquanto o provedor discava (operador desligou), o
 *       encerramento é pedido ao provedor depois do commit; recusa → {@code FAILED} como não discada.</li>
 * </ol>
 * O ramal enviado ao provedor é o do operador na conta que disca ({@link AgentEndpoints}).
 */
@Component
class DialJob implements JobHandler<DialJob.Payload> {

    static final String TYPE = "telephony.dial";
    private static final Logger LOG = LoggerFactory.getLogger(DialJob.class);

    private final CallRepository calls;
    private final TelephonyAccountRepository accounts;
    private final AgentRepository agents;
    private final ProviderResolver resolver;
    private final AccountRouter router;
    private final AgentEndpoints endpoints;
    private final ContactGate gate;
    private final CallLifecycle lifecycle;
    private final ProviderCalls providerCalls;
    private final TenantExecution tenantExecution;
    private final TelephonyMetrics metrics;
    private final TelephonyProperties properties;
    private final Clock clock;

    DialJob(CallRepository calls, TelephonyAccountRepository accounts, AgentRepository agents, ProviderResolver resolver,
            AccountRouter router, AgentEndpoints endpoints, ContactGate gate, CallLifecycle lifecycle,
            ProviderCalls providerCalls, TenantExecution tenantExecution, TelephonyMetrics metrics,
            TelephonyProperties properties, Clock clock) {
        this.calls = calls;
        this.accounts = accounts;
        this.agents = agents;
        this.resolver = resolver;
        this.router = router;
        this.endpoints = endpoints;
        this.gate = gate;
        this.lifecycle = lifecycle;
        this.providerCalls = providerCalls;
        this.tenantExecution = tenantExecution;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    record Payload(UUID callId) {
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    @Override
    public int maxAttempts() {
        return properties.dialMaxAttempts();
    }

    @Override
    public boolean transactional() {
        return false;
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        UUID tenantId = context.tenantId();
        Optional<Dial> prepared = tenantExecution.callInTenant(tenantId, "telephony.dial.prepare",
                () -> prepare(payload.callId()));
        if (prepared.isEmpty()) {
            return;
        }
        Dial dial = prepared.get();
        DialResult result;
        try {
            result = originate(dial);
        } catch (TransientProviderException primaryFailure) {
            metrics.providerDial(dial.providerName(), dial.origin(),
                    primaryFailure.notProcessed() ? "transient_not_processed" : "transient");
            Optional<Dial> fallback = primaryFailure.notProcessed()
                    ? tenantExecution.callInTenant(tenantId, "telephony.dial.fallback", () -> switchToFallback(dial))
                    : Optional.empty();
            if (fallback.isEmpty()) {
                throw exhaustedOr(context, dial, primaryFailure);
            }
            metrics.fallback(dial.providerName(), fallback.get().providerName(), dial.origin(), 1);
            LOG.atWarn().addKeyValue("callId", dial.callId()).addKeyValue("fromAccountId", dial.accountId())
                    .addKeyValue("toAccountId", fallback.get().accountId())
                    .log("Discagem desviada para a conta de contingência");
            try {
                result = originate(fallback.get());
            } catch (TransientProviderException fallbackFailure) {
                metrics.providerDial(fallback.get().providerName(), dial.origin(), "transient");
                throw exhaustedOr(context, fallback.get(), fallbackFailure);
            } catch (RuntimeException unexpected) {
                throw unexpected(context, fallback.get(), unexpected);
            }
        } catch (RuntimeException unexpected) {
            throw unexpected(context, dial, unexpected);
        }
        DialResult outcome = result;
        tenantExecution.inTenant(tenantId, "telephony.dial.result", () -> record(dial.callId(), outcome));
    }

    // ------------------------------------------------------------------ fase 1: preparo

    private Optional<Dial> prepare(UUID callId) {
        Optional<Call> found = calls.findByIdForUpdate(callId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Call call = found.get();
        boolean retry = call.isAwaitingProvider();
        if (call.getStatus() != CallStatus.QUEUED && !retry) {
            return Optional.empty();
        }
        Optional<TelephonyAccount> account = accounts.findById(call.getAccountId()).filter(TelephonyAccount::isActive);
        Optional<TelephonyProvider> provider = account.flatMap(a -> resolver.provider(a.getProvider()));
        Optional<Agent> agent = agents.findByUserId(call.getOperatorId()).filter(Agent::isActive);
        Optional<String> extension = agent.flatMap(a -> account.flatMap(acc -> endpoints.extensionFor(a, acc.getId())));
        if (provider.isEmpty() || extension.isEmpty()) {
            unavailable(call, provider.isEmpty() ? "ACCOUNT_UNAVAILABLE" : "AGENT_UNAVAILABLE",
                    provider.isEmpty() ? "Conta de telefonia inativa ou sem adaptador" : "Operador sem ramal ativo nesta conta");
            return Optional.empty();
        }
        if (!retry) {
            ContactDecision decision = gate.authorizeAndRecord(new ContactGate.ContactRequest(call.getDebtorId(),
                    call.getCreditorId(), "PHONE", call.getNumber(), null, call.getOrigin().gateOrigin(), "call",
                    call.getId()));
            if (!decision.allowed()) {
                List<String> reasons = decision.reasons().stream().map(Enum::name).toList();
                call.block(String.join(",", reasons), Instant.now(clock));
                calls.save(call);
                lifecycle.blocked(call, reasons, decision.nextAllowedAt(), "dial");
                return Optional.empty();
            }
            call.dialRequested(decision.attemptId(), Instant.now(clock));
            calls.save(call);
        }
        // nova execução com o pedido sem resposta: repete na mesma conta, com a mesma tentativa do compliance
        return Optional.of(dial(call, account.get(), provider.get(), extension.get()));
    }

    /** Desvio para a contingência, gravado antes do novo pedido (os eventos dela precisam achar a conta). */
    private Optional<Dial> switchToFallback(Dial dial) {
        Optional<Call> found = calls.findByIdForUpdate(dial.callId()).filter(Call::isAwaitingProvider);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Call call = found.get();
        Optional<Agent> agent = agents.findByUserId(call.getOperatorId()).filter(Agent::isActive);
        Optional<Fallback> fallback = agent.flatMap(a -> fallback(call, a));
        if (fallback.isEmpty()) {
            return Optional.empty();
        }
        call.switchToFallback(fallback.get().account());
        calls.save(call);
        return Optional.of(dial(call, fallback.get().account(), fallback.get().provider(), fallback.get().extension()));
    }

    /** Contingência da regra do caso, se habilitada, ainda não usada e com ramal do operador. */
    private Optional<Fallback> fallback(Call call, Agent agent) {
        if (!properties.routing().fallbackEnabled() || call.getFallbackFromAccountId() != null) {
            return Optional.empty();
        }
        AccountRouter.Route route;
        try {
            route = router.forClickToCall(agent, call.getCreditorId(), call.getPortfolioId());
        } catch (ConflictException e) {
            return Optional.empty();
        }
        Optional<TelephonyAccount> candidate = route.primary().getId().equals(call.getAccountId())
                ? route.fallbackAccount() : Optional.of(route.primary());
        return candidate.filter(a -> !a.getId().equals(call.getAccountId())).flatMap(a ->
                resolver.provider(a.getProvider()).flatMap(p -> endpoints.extensionFor(agent, a.getId())
                        .map(ext -> new Fallback(a, p, ext))));
    }

    private Dial dial(Call call, TelephonyAccount account, TelephonyProvider provider, String extension) {
        return new Dial(call.getId(), account.getId(), name(account), provider, resolver.context(account), extension,
                call.getNumber(), call.isRecordingRequested(), call.isRecordingAnnounced(), call.getOrigin().name());
    }

    // ------------------------------------------------------------------ fase 2: provedor

    private DialResult originate(Dial dial) {
        return dial.provider().originate(new OriginateRequest(dial.context(), dial.callId(), dial.extension(),
                dial.number(), CallInstructions.CLICK_TO_CALL_RING_SECONDS, dial.recordingRequested(),
                dial.recordingAnnounced()));
    }

    /** Falha inesperada do adaptador: como a transitória (nova tentativa; na última, a chamada é encerrada). */
    private RuntimeException unexpected(JobContext context, Dial dial, RuntimeException e) {
        metrics.providerDial(dial.providerName(), dial.origin(), "error");
        closeIfLastAttempt(context, dial);
        return e;
    }

    /** Falha transitória sem contingência: na última tentativa encerra a chamada; devolve a exceção a relançar. */
    private RuntimeException exhaustedOr(JobContext context, Dial dial, TransientProviderException e) {
        metrics.dial(dial.origin(), "transient");
        closeIfLastAttempt(context, dial);
        return e;
    }

    /** Na última tentativa o pedido sem resposta não fica aberto: {@code FAILED/RETRIES_EXHAUSTED}, não discada. */
    private void closeIfLastAttempt(JobContext context, Dial dial) {
        if (context.attempt() >= maxAttempts()) {
            metrics.jobFailure(TYPE);
            metrics.dial(dial.origin(), "exhausted");
            tenantExecution.inTenant(context.tenantId(), "telephony.dial_exhausted", () ->
                    calls.findByIdForUpdate(dial.callId()).filter(Call::isAwaitingProvider).ifPresent(c -> {
                        c.providerRejected("RETRIES_EXHAUSTED", "Provedor indisponível", Instant.now(clock));
                        calls.save(c);
                        lifecycle.ended(c);
                    }));
        }
    }

    // ------------------------------------------------------------------ fase 3: resultado

    private void record(UUID callId, DialResult result) {
        Call call = calls.findByIdForUpdate(callId).orElseThrow();
        if (result.accepted()) {
            accepted(accounts.findById(call.getAccountId()).orElseThrow(), call, result.providerCallId(), calls,
                    lifecycle, providerCalls, HangupReason.OPERATOR);
            return;
        }
        LOG.atWarn().addKeyValue("callId", call.getId()).addKeyValue("provider", call.getProvider())
                .addKeyValue("errorCode", result.errorCode()).log("Provedor recusou a discagem");
        metrics.dial(call.getOrigin().name(), "rejected");
        metrics.providerDial(call.getProvider().name().toLowerCase(Locale.ROOT), call.getOrigin().name(), "rejected");
        if (call.isAwaitingProvider()) {
            call.providerRejected(result.errorCode(), result.errorDetail(), Instant.now(clock));
            calls.save(call);
            lifecycle.ended(call);
        }
    }

    /**
     * Aceite do provedor (comum ao discador). Chamada já final sem o pedido (recusada pela conciliação ou nas
     * tentativas esgotadas) ou com outro id: o que o provedor criou é encerrado lá, sem exceção (no discador ela
     * desfaria o resultado do lote inteiro). Gravado agora numa chamada já final → encerrada aqui enquanto o provedor
     * discava (o evento grava o id antes, quando o traz): encerra também lá.
     */
    static void accepted(TelephonyAccount account, Call call, String providerCallId, CallRepository calls,
                         CallLifecycle lifecycle, ProviderCalls providerCalls, HangupReason reason) {
        boolean bindable = call.getDialStartedAt() != null
                && (call.getProviderCallId() == null || call.getProviderCallId().equals(providerCallId));
        if (!bindable) {
            LOG.atWarn().addKeyValue("callId", call.getId()).addKeyValue("status", call.getStatus())
                    .log("Aceite do provedor para chamada já encerrada ou com outro id; encerrando no provedor");
            providerCalls.hangupAfterCommit(account, call.getId(), providerCallId, null, reason);
            return;
        }
        boolean bound = call.providerAccepted(providerCallId);
        calls.save(call);
        lifecycle.dialed(call);
        if (bound && call.getStatus().isTerminal()) {
            providerCalls.hangupAfterCommit(account, call, reason);
        }
    }

    /** Conta ou operador indisponível: antes do pedido, falha antes da discagem; na nova execução, recusa. */
    private void unavailable(Call call, String code, String detail) {
        Instant now = Instant.now(clock);
        if (call.isAwaitingProvider()) {
            call.providerRejected(code, detail, now);
        } else {
            call.failBeforeDialing(code, detail, now);
        }
        calls.save(call);
        lifecycle.ended(call);
    }

    private static String name(TelephonyAccount account) {
        return account.getProvider().name().toLowerCase(Locale.ROOT);
    }

    private record Fallback(TelephonyAccount account, TelephonyProvider provider, String extension) {
    }

    /**
     * Pedido de discagem montado na fase de preparo (fora da transação só circulam estes valores). O número em
     * claro vai ao provedor e nunca a log: {@link #toString()} não o inclui.
     */
    private record Dial(UUID callId, UUID accountId, String providerName, TelephonyProvider provider,
                        ProviderContext context, String extension, String number, boolean recordingRequested,
                        boolean recordingAnnounced, String origin) {

        @Override
        public String toString() {
            return "Dial[callId=" + callId + ", accountId=" + accountId + ", provider=" + providerName + "]";
        }
    }
}
