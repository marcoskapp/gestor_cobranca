package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.HangupReason;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.HangupRequest;
import br.com.gestorcobranca.telephony.application.service.ProviderCalls.ProviderLine;
import br.com.gestorcobranca.telephony.domain.model.Call;
import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;
import br.com.gestorcobranca.telephony.domain.repository.CallRepository;
import br.com.gestorcobranca.telephony.domain.repository.TelephonyAccountRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Nova tentativa de encerrar a chamada no provedor ({@code telephony.hangup}) depois de uma falha
 * transitória no pedido feito logo após o commit ({@link ProviderCalls}). Sem transação durante o HTTP
 * (decisão 852): lê a chamada numa transação curta e chama o provedor fora dela; falha transitória
 * relança (backoff do worker), recusa encerra o job (a chamada já terminou no provedor).
 */
@Component
class ProviderHangupJob implements JobHandler<ProviderHangupJob.Payload> {

    static final String TYPE = "telephony.hangup";

    private final CallRepository calls;
    private final TelephonyAccountRepository accounts;
    private final ProviderResolver resolver;
    private final ProviderCalls providerCalls;
    private final TenantExecution tenantExecution;

    ProviderHangupJob(CallRepository calls, TelephonyAccountRepository accounts, ProviderResolver resolver,
                      ProviderCalls providerCalls, TenantExecution tenantExecution) {
        this.calls = calls;
        this.accounts = accounts;
        this.resolver = resolver;
        this.providerCalls = providerCalls;
        this.tenantExecution = tenantExecution;
    }

    /** @param reason {@link HangupReason} */
    record Payload(UUID callId, String reason) {
    }

    static String key(UUID callId) {
        return "hangup:" + callId;
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
        // o provedor encerra pelo próprio tempo limite; depois de ~2,5 min de tentativas não há o que insistir
        return 4;
    }

    @Override
    public boolean transactional() {
        return false;
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        Optional<Pending> pending = tenantExecution.callInTenant(context.tenantId(), "telephony.hangup.prepare", () ->
                calls.findById(payload.callId()).filter(c -> c.getProviderCallId() != null).flatMap(call ->
                        accounts.findById(call.getAccountId()).map(account -> pending(account, call,
                                HangupReason.valueOf(payload.reason())))));
        pending.ifPresent(p -> providerCalls.hangup(p.line(), p.request(), false));
    }

    private Pending pending(TelephonyAccount account, Call call, HangupReason reason) {
        return new Pending(providerCalls.line(account), new HangupRequest(resolver.context(account), call.getId(),
                call.getProviderCallId(), reason, call.getProviderLegId()));
    }

    private record Pending(ProviderLine line, HangupRequest request) {
    }
}
