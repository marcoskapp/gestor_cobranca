package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.compliance.application.port.in.ContactGate;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactDecision;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.DialResult;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.OriginateRequest;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Discagem do clique para ligar ({@code telephony.dial}), no tenant da chamada, como sistema:
 * <ol>
 *   <li>chamada que não está mais {@code QUEUED} → nada a fazer (reexecução idempotente);</li>
 *   <li>conta inativa/sem adaptador ou operador sem ramal → {@code FAILED};</li>
 *   <li>{@code ContactGate#authorizeAndRecord} imediatamente antes de discar: negado →
 *       {@code FAILED/CONTACT_DENIED}, operador liberado; permitido → tentativa registrada;</li>
 *   <li>provedor: aceite → {@code DIALING}; recusa → {@code FAILED}; falha transitória que
 *       comprovadamente não criou a chamada → a mesma chamada vai, uma única vez, para a conta de
 *       contingência da regra (se o operador tem ramal nela); demais falhas transitórias → exceção
 *       (transação desfeita, inclusive a tentativa no compliance e o desvio, e o job reagendado). Na
 *       última tentativa a chamada é encerrada como {@code FAILED/RETRIES_EXHAUSTED} em transação
 *       própria.</li>
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
    private final TenantExecution tenantExecution;
    private final TelephonyMetrics metrics;
    private final TelephonyProperties properties;
    private final Clock clock;

    DialJob(CallRepository calls, TelephonyAccountRepository accounts, AgentRepository agents, ProviderResolver resolver,
            AccountRouter router, AgentEndpoints endpoints, ContactGate gate, CallLifecycle lifecycle,
            TenantExecution tenantExecution, TelephonyMetrics metrics, TelephonyProperties properties, Clock clock) {
        this.calls = calls;
        this.accounts = accounts;
        this.agents = agents;
        this.resolver = resolver;
        this.router = router;
        this.endpoints = endpoints;
        this.gate = gate;
        this.lifecycle = lifecycle;
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
    public void handle(JobContext context, Payload payload) {
        Optional<Call> found = calls.findById(payload.callId());
        if (found.isEmpty() || found.get().getStatus() != CallStatus.QUEUED) {
            return;
        }
        Call call = found.get();
        Optional<TelephonyAccount> account = accounts.findById(call.getAccountId()).filter(TelephonyAccount::isActive);
        Optional<TelephonyProvider> provider = account.flatMap(a -> resolver.provider(a.getProvider()));
        Optional<Agent> agent = agents.findByUserId(call.getOperatorId()).filter(Agent::isActive);
        Optional<String> extension = agent.flatMap(a -> account.flatMap(acc -> endpoints.extensionFor(a, acc.getId())));
        if (provider.isEmpty() || extension.isEmpty()) {
            fail(call, provider.isEmpty() ? "ACCOUNT_UNAVAILABLE" : "AGENT_UNAVAILABLE",
                    provider.isEmpty() ? "Conta de telefonia inativa ou sem adaptador"
                            : "Operador sem ramal ativo nesta conta");
            return;
        }
        ContactDecision decision = gate.authorizeAndRecord(new ContactGate.ContactRequest(call.getDebtorId(),
                call.getCreditorId(), "PHONE", call.getNumber(), null, call.getOrigin().gateOrigin(), "call", call.getId()));
        if (!decision.allowed()) {
            List<String> reasons = decision.reasons().stream().map(Enum::name).toList();
            call.block(String.join(",", reasons), Instant.now(clock));
            calls.save(call);
            lifecycle.blocked(call, reasons, decision.nextAllowedAt(), "dial");
            return;
        }
        DialResult result;
        try {
            result = originate(provider.get(), account.get(), extension.get(), call);
        } catch (TransientProviderException primaryFailure) {
            Optional<Fallback> fallback = primaryFailure.notProcessed() ? fallback(call, agent.get()) : Optional.empty();
            metrics.providerDial(name(account.get()), call.getOrigin().name(),
                    primaryFailure.notProcessed() ? "transient_not_processed" : "transient");
            if (fallback.isEmpty()) {
                throw exhaustedOr(context, call, primaryFailure);
            }
            call.switchToFallback(fallback.get().account());
            metrics.fallback(name(account.get()), name(fallback.get().account()), call.getOrigin().name(), 1);
            LOG.atWarn().addKeyValue("callId", call.getId()).addKeyValue("fromAccountId", account.get().getId())
                    .addKeyValue("toAccountId", fallback.get().account().getId())
                    .log("Discagem desviada para a conta de contingência");
            try {
                result = originate(fallback.get().provider(), fallback.get().account(), fallback.get().extension(), call);
            } catch (TransientProviderException fallbackFailure) {
                metrics.providerDial(name(fallback.get().account()), call.getOrigin().name(), "transient");
                throw exhaustedOr(context, call, fallbackFailure);
            }
        }
        Instant now = Instant.now(clock);
        if (result.accepted()) {
            call.dialing(result.providerCallId(), decision.attemptId(), now);
            calls.save(call);
            lifecycle.dialed(call);
        } else {
            LOG.atWarn().addKeyValue("callId", call.getId()).addKeyValue("provider", call.getProvider())
                    .addKeyValue("errorCode", result.errorCode()).log("Provedor recusou a discagem");
            metrics.dial(call.getOrigin().name(), "rejected");
            metrics.providerDial(call.getProvider().name().toLowerCase(java.util.Locale.ROOT), call.getOrigin().name(),
                    "rejected");
            fail(call, result.errorCode(), result.errorDetail());
        }
    }

    private DialResult originate(TelephonyProvider provider, TelephonyAccount account, String extension, Call call) {
        return provider.originate(new OriginateRequest(resolver.context(account), call.getId(), extension,
                call.getNumber(), 30, call.isRecordingRequested(), call.isRecordingAnnounced()));
    }

    /** Contingência da regra do caso, se habilitada, ainda não usada e com ramal do operador. */
    private Optional<Fallback> fallback(Call call, Agent agent) {
        if (!properties.routing().fallbackEnabled() || call.getFallbackFromAccountId() != null) {
            return Optional.empty();
        }
        AccountRouter.Route route;
        try {
            route = router.forClickToCall(agent, call.getCreditorId(), call.getPortfolioId());
        } catch (br.com.gestorcobranca.shared.domain.ConflictException e) {
            return Optional.empty();
        }
        Optional<TelephonyAccount> candidate = route.primary().getId().equals(call.getAccountId())
                ? route.fallbackAccount() : Optional.of(route.primary());
        return candidate.filter(a -> !a.getId().equals(call.getAccountId())).flatMap(a ->
                resolver.provider(a.getProvider()).flatMap(p -> endpoints.extensionFor(agent, a.getId())
                        .map(ext -> new Fallback(a, p, ext))));
    }

    /** Falha transitória sem contingência: na última tentativa encerra a chamada; devolve a exceção a relançar. */
    private RuntimeException exhaustedOr(JobContext context, Call call, TransientProviderException e) {
        metrics.dial(call.getOrigin().name(), "transient");
        if (context.attempt() >= maxAttempts()) {
            metrics.jobFailure(TYPE);
            metrics.dial(call.getOrigin().name(), "exhausted");
            // a tentativa no compliance é desfeita com esta transação; o encerramento vale em transação própria
            tenantExecution.inTenant(context.tenantId(), "telephony.dial_exhausted", () ->
                    calls.findByIdForUpdate(call.getId()).filter(c -> c.getStatus() == CallStatus.QUEUED)
                            .ifPresent(c -> fail(c, "RETRIES_EXHAUSTED", "Provedor indisponível")));
        }
        return e;
    }

    private static String name(TelephonyAccount account) {
        return account.getProvider().name().toLowerCase(java.util.Locale.ROOT);
    }

    private record Fallback(TelephonyAccount account, TelephonyProvider provider, String extension) {
    }

    private void fail(Call call, String code, String detail) {
        call.failBeforeDialing(code, detail, Instant.now(clock));
        calls.save(call);
        lifecycle.ended(call);
    }
}
