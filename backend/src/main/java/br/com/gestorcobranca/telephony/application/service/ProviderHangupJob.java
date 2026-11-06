package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.HangupReason;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.HangupRequest;
import br.com.gestorcobranca.telephony.application.service.ProviderCalls.ProviderLine;
import br.com.gestorcobranca.telephony.domain.model.Call;
import br.com.gestorcobranca.telephony.domain.repository.CallRepository;
import br.com.gestorcobranca.telephony.domain.repository.TelephonyAccountRepository;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Encerramento da chamada no provedor ({@code telephony.hangup}), gravado na transação que encerrou a chamada aqui
 * e executado logo após o commit ({@link ProviderCalls}). Sem transação durante o HTTP (decisão 852): lê a conta
 * numa transação curta e chama o provedor fora dela. Idempotente: o estado local já é final; repetir o pedido numa
 * chamada que já terminou no provedor resulta em recusa, tratada como concluída. Falha transitória relança (backoff
 * do worker).
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

    /**
     * Somente ids (sem dado pessoal).
     *
     * @param reason         {@link HangupReason}
     * @param accountId      conta que encerra (a da chamada, ou a que aceitou um pedido que não pôde ser gravado)
     * @param providerCallId id no provedor; pode diferir do gravado na chamada (aceite tardio com outro id)
     * @param providerLegId  perna no provedor; pode faltar
     */
    record Payload(UUID callId, String reason, UUID accountId, String providerCallId, String providerLegId) {
    }

    /**
     * Um encerramento ativo por chamada <b>e</b> id no provedor: o aceite tardio com outro id (outra chamada criada
     * lá) é encerrado por um job próprio, sem ser absorvido pelo encerramento pendente da chamada. Id longo demais para
     * a chave ({@code dedup_key varchar(200)}) entra pelo SHA-256.
     */
    static String key(UUID callId, String providerCallId) {
        String id = providerCallId.length() <= MAX_RAW_PROVIDER_ID ? providerCallId
                : "sha256-" + HexFormat.of().formatHex(sha256(providerCallId));
        return "hangup:" + callId + ":" + id;
    }

    private static final int MAX_RAW_PROVIDER_ID = 120;

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
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
        // o pedido imediato e mais 4 tentativas (~2,5 min): depois disso o provedor já encerrou pelo próprio tempo limite
        return 5;
    }

    @Override
    public boolean transactional() {
        return false;
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        Optional<Pending> pending = tenantExecution.callInTenant(context.tenantId(), "telephony.hangup.prepare",
                () -> prepare(payload));
        pending.ifPresent(p -> providerCalls.sendHangup(p.line(), p.request()));
    }

    private Optional<Pending> prepare(Payload payload) {
        // jobs gravados antes de o payload levar conta e ids do provedor: valem os da chamada
        Optional<Call> call = payload.accountId() != null && payload.providerCallId() != null ? Optional.empty()
                : calls.findById(payload.callId());
        UUID accountId = payload.accountId() != null ? payload.accountId() : call.map(Call::getAccountId).orElse(null);
        String providerCallId = payload.providerCallId() != null ? payload.providerCallId()
                : call.map(Call::getProviderCallId).orElse(null);
        String legId = payload.providerLegId() != null ? payload.providerLegId()
                : call.map(Call::getProviderLegId).orElse(null);
        if (accountId == null || providerCallId == null) {
            return Optional.empty();
        }
        HangupReason reason = HangupReason.valueOf(payload.reason());
        return accounts.findById(accountId).map(account -> new Pending(providerCalls.line(account),
                new HangupRequest(resolver.context(account), payload.callId(), providerCallId, reason, legId)));
    }

    private record Pending(ProviderLine line, HangupRequest request) {
    }
}
