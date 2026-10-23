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
 *   <li>provedor: aceite → {@code DIALING}; recusa → {@code FAILED}; falha transitória → exceção
 *       (transação desfeita, inclusive a tentativa no compliance, e o job reagendado). Na última
 *       tentativa a chamada é encerrada como {@code FAILED/RETRIES_EXHAUSTED} em transação própria.</li>
 * </ol>
 */
@Component
class DialJob implements JobHandler<DialJob.Payload> {

    static final String TYPE = "telephony.dial";
    private static final Logger LOG = LoggerFactory.getLogger(DialJob.class);

    private final CallRepository calls;
    private final TelephonyAccountRepository accounts;
    private final AgentRepository agents;
    private final ProviderResolver resolver;
    private final ContactGate gate;
    private final CallLifecycle lifecycle;
    private final TenantExecution tenantExecution;
    private final TelephonyMetrics metrics;
    private final TelephonyProperties properties;
    private final Clock clock;

    DialJob(CallRepository calls, TelephonyAccountRepository accounts, AgentRepository agents, ProviderResolver resolver,
            ContactGate gate, CallLifecycle lifecycle, TenantExecution tenantExecution, TelephonyMetrics metrics,
            TelephonyProperties properties, Clock clock) {
        this.calls = calls;
        this.accounts = accounts;
        this.agents = agents;
        this.resolver = resolver;
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
        if (provider.isEmpty() || agent.isEmpty()) {
            fail(call, provider.isEmpty() ? "ACCOUNT_UNAVAILABLE" : "AGENT_UNAVAILABLE",
                    provider.isEmpty() ? "Conta de telefonia inativa ou sem adaptador" : "Operador sem ramal ativo");
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
            result = provider.get().originate(new OriginateRequest(resolver.context(account.get()), call.getId(),
                    agent.get().getExtension(), call.getNumber(), 30, call.isRecordingRequested(),
                    call.isRecordingAnnounced()));
        } catch (TransientProviderException e) {
            metrics.dial(call.getOrigin().name(), "transient");
            if (context.attempt() >= maxAttempts()) {
                metrics.jobFailure(TYPE);
                metrics.dial(call.getOrigin().name(), "exhausted");
                // a tentativa no compliance é desfeita com esta transação; o encerramento vale em transação própria
                tenantExecution.inTenant(context.tenantId(), "telephony.dial_exhausted", () ->
                        calls.findByIdForUpdate(call.getId()).filter(c -> c.getStatus() == CallStatus.QUEUED)
                                .ifPresent(c -> fail(c, "RETRIES_EXHAUSTED", "Provedor indisponível")));
            }
            throw e;
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
            fail(call, result.errorCode(), result.errorDetail());
        }
    }

    private void fail(Call call, String code, String detail) {
        call.failBeforeDialing(code, detail, Instant.now(clock));
        calls.save(call);
        lifecycle.ended(call);
    }
}
