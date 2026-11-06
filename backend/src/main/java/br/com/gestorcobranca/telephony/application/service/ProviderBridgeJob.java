package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.jobs.PermanentJobFailure;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.BridgeRequest;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.HangupReason;
import br.com.gestorcobranca.telephony.application.service.ProviderCalls.ProviderLine;
import br.com.gestorcobranca.telephony.domain.model.Call;
import br.com.gestorcobranca.telephony.domain.model.CallOrigin;
import br.com.gestorcobranca.telephony.domain.model.CallStatus;
import br.com.gestorcobranca.telephony.domain.model.DialerCampaign;
import br.com.gestorcobranca.telephony.domain.model.HangupSide;
import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;
import br.com.gestorcobranca.telephony.domain.repository.AgentRepository;
import br.com.gestorcobranca.telephony.domain.repository.CallRepository;
import br.com.gestorcobranca.telephony.domain.repository.DialerCampaignRepository;
import br.com.gestorcobranca.telephony.domain.repository.TelephonyAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Conexão da chamada atendida ao operador no provedor ({@code telephony.bridge}), gravada na transação do evento que
 * decidiu a conexão (operador {@code ON_CALL}, {@code connected_at}) e executada logo após o commit
 * ({@link ProviderCalls}). Sem transação durante o HTTP (decisão 852):
 * <ol>
 *   <li><b>preparo</b> (transação curta, chamada em {@code FOR UPDATE}): a chamada não está mais conectada àquele
 *       operador (terminou, foi desfeita ou mudou de operador) → nada a fazer; operador sem ramal ou provedor
 *       indisponível na conta → conexão desfeita; senão grava a marca {@code bridge_requested_at}
 *       ({@link Call#bridgeRequested}) antes do HTTP;</li>
 *   <li><b>provedor</b> (sem transação nem lock): {@code bridge};</li>
 *   <li><b>compensação</b> (transação curta), se o provedor não conectou: {@link #bridgeFailed}.</li>
 * </ol>
 * O pedido ao provedor nunca se repete: repetir a conexão num provedor que já a fez derrubaria a conversa em curso
 * (o provedor recoloca a chamada no ramal). A marca durável decide: falha antes dela confirmada (no preparo) → a nova
 * tentativa do job pede normalmente; marca existente (instância caiu durante o pedido, lease expirado) → não reenvia
 * nem desfaz, registra {@code interrupted} e deixa a chamada para os eventos do provedor e a conciliação. Falha
 * transitória ou recusa do provedor desfaz a conexão (o devedor não fica esperando); falha ao desfazer →
 * {@link PermanentJobFailure} (DEAD, alarme {@code jobs.dead}), sem novo pedido.
 */
@Component
class ProviderBridgeJob implements JobHandler<ProviderBridgeJob.Payload> {

    static final String TYPE = "telephony.bridge";
    private static final Logger LOG = LoggerFactory.getLogger(ProviderBridgeJob.class);

    private final CallRepository calls;
    private final TelephonyAccountRepository accounts;
    private final AgentRepository agents;
    private final DialerCampaignRepository campaigns;
    private final AgentEndpoints endpoints;
    private final ProviderResolver resolver;
    private final ProviderCalls providerCalls;
    private final CallLifecycle lifecycle;
    private final TelephonyMetrics metrics;
    private final TenantExecution tenantExecution;
    private final Clock clock;

    ProviderBridgeJob(CallRepository calls, TelephonyAccountRepository accounts, AgentRepository agents,
                      DialerCampaignRepository campaigns, AgentEndpoints endpoints, ProviderResolver resolver,
                      ProviderCalls providerCalls, CallLifecycle lifecycle, TelephonyMetrics metrics,
                      TenantExecution tenantExecution, Clock clock) {
        this.calls = calls;
        this.accounts = accounts;
        this.agents = agents;
        this.campaigns = campaigns;
        this.endpoints = endpoints;
        this.resolver = resolver;
        this.providerCalls = providerCalls;
        this.lifecycle = lifecycle;
        this.metrics = metrics;
        this.tenantExecution = tenantExecution;
        this.clock = clock;
    }

    /** Somente ids (sem dado pessoal). */
    record Payload(UUID callId, UUID accountId, UUID operatorId) {
    }

    static String key(UUID callId) {
        return "bridge:" + callId;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    /**
     * Novas tentativas só cobrem falhas antes do pedido (preparo); depois da marca, a execução seguinte apenas
     * registra a interrupção. Poucas: a conexão só faz sentido enquanto o devedor espera na linha.
     */
    @Override
    public int maxAttempts() {
        return 3;
    }

    @Override
    public boolean transactional() {
        return false;
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        UUID tenantId = context.tenantId();
        // falha aqui (antes da marca confirmada) não pediu nada ao provedor: a nova tentativa do job pede
        Optional<Pending> pending = tenantExecution.callInTenant(tenantId, "telephony.bridge.prepare",
                () -> prepare(payload));
        if (pending.isEmpty()) {
            return;
        }
        if (!providerCalls.sendBridge(pending.get().line(), pending.get().request())) {
            compensate(tenantId, payload);
        }
    }

    /**
     * Sob o lock da chamada: confere que ela continua conectada àquele operador e grava a marca
     * {@code bridge_requested_at} antes do HTTP. Marca já existente → uma execução anterior pode ter feito o pedido
     * (queda durante o HTTP, ou falha depois dele): não reenvia nem desfaz.
     */
    private Optional<Pending> prepare(Payload payload) {
        Optional<Call> found = calls.findByIdForUpdate(payload.callId());
        Optional<TelephonyAccount> account = accounts.findById(payload.accountId());
        String provider = account.map(ProviderCalls::name).orElse("unknown");
        if (found.isEmpty() || account.isEmpty() || !connectedTo(found.get(), payload.operatorId())) {
            // terminou (o devedor desligou), foi desfeita ou mudou de operador antes do pedido: nada a conectar
            metrics.providerCommand(provider, "bridge", "stale");
            return Optional.empty();
        }
        Call call = found.get();
        if (call.isBridgeRequested()) {
            metrics.providerCommand(provider, "bridge", "interrupted");
            LOG.atWarn().addKeyValue("callId", call.getId())
                    .log("Conexão ao operador já pedida numa execução anterior interrompida; não reenviada");
            return Optional.empty();
        }
        ProviderLine line = providerCalls.line(account.get());
        Optional<String> extension = agents.findByUserId(payload.operatorId())
                .flatMap(agent -> endpoints.extensionFor(agent, account.get().getId()));
        if (line.adapter().isEmpty() || extension.isEmpty()) {
            metrics.providerCommand(provider, "bridge", "skipped");
            bridgeFailed(call.getId(), payload.operatorId());
            return Optional.empty();
        }
        call.bridgeRequested(Instant.now(clock));
        calls.save(call);
        return Optional.of(new Pending(line, new BridgeRequest(resolver.context(account.get()), call.getId(),
                call.getProviderCallId(), extension.get(), call.getProviderLegId(), payload.operatorId())));
    }

    /**
     * Desfaz a conexão que o provedor não fez. Falha aqui não pode ser repetida pelo job (a marca impede um novo
     * pedido, e a nova execução não desfaria): o job vai para DEAD (alarme {@code jobs.dead}) — operador e devedor
     * podem ficar presos até a conciliação ou intervenção.
     */
    private void compensate(UUID tenantId, Payload payload) {
        try {
            tenantExecution.inTenant(tenantId, "telephony.bridge_failed",
                    () -> bridgeFailed(payload.callId(), payload.operatorId()));
        } catch (RuntimeException e) {
            metrics.jobFailure(TYPE);
            LOG.atError().addKeyValue("callId", payload.callId()).setCause(e)
                    .log("Falha ao desfazer a conexão que o provedor não fez");
            throw new PermanentJobFailure("Falha ao desfazer a conexão ao operador não feita pelo provedor", e);
        }
    }

    private static boolean connectedTo(Call call, UUID operatorId) {
        return call.getStatus() == CallStatus.ANSWERED && call.getConnectedAt() != null
                && operatorId.equals(call.getOperatorId());
    }

    /**
     * O provedor não conectou a chamada ao operador (depois do commit da conexão): operador liberado, chamada
     * sem operador (abandono no discador; perdida na recebida) e encerramento no provedor (job próprio, na mesma
     * transação). Sem efeito se a chamada já terminou ou mudou de operador.
     */
    private void bridgeFailed(UUID callId, UUID operatorId) {
        Call call = calls.findByIdForUpdate(callId).orElseThrow();
        if (!connectedTo(call, operatorId)) {
            return;
        }
        TelephonyAccount account = accounts.findById(call.getAccountId()).orElseThrow();
        agents.findByUserIdForUpdate(operatorId).ifPresent(agent -> lifecycle.releaseAfterFailedBridge(agent, callId));
        call.bridgeFailed();
        Instant now = Instant.now(clock);
        if (call.getOrigin() == CallOrigin.DIALER) {
            String mode = campaigns.findById(call.getCampaignId()).map(DialerCampaign::getMode).map(Enum::name)
                    .orElse("UNKNOWN");
            metrics.live(mode, "bridge_failed");
            call.end(CallStatus.COMPLETED, now, HangupSide.SYSTEM, null);
        } else {
            call.end(CallStatus.NO_ANSWER, now, HangupSide.SYSTEM, CallInstructions.NO_AGENT);
        }
        providerCalls.hangup(account, call, HangupReason.ABANDONED);
        lifecycle.ended(call);
    }

    private record Pending(ProviderLine line, BridgeRequest request) {
    }
}
