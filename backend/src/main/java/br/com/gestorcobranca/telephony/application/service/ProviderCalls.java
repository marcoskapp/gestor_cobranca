package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.BridgeRequest;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.HangupReason;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.HangupRequest;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.ProviderRejectedException;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.TransientProviderException;
import br.com.gestorcobranca.telephony.domain.model.Call;
import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Comandos de chamada em andamento ao provedor ({@code bridge} e {@code hangup}) <b>depois do commit</b>
 * da transação que decidiu o estado local (TEL-02). Assim o HTTP ao provedor não segura conexão nem o
 * lock da chamada, e os eventos que o provedor mandar em resposta já encontram o estado confirmado.
 * <ul>
 *   <li>encerramento: falha transitória reagenda o pedido no job {@code telephony.hangup} (com
 *       retentativas); recusa (a chamada já terminou no provedor) só é registrada;</li>
 *   <li>conexão ao operador: o resultado volta ao chamador, que desfaz a conexão em transação própria
 *       quando o provedor não conecta.</li>
 * </ul>
 * Os comandos rodam logo após o commit numa thread do executor {@code telephonyCommandExecutor} (a thread que
 * confirmou ainda segura a conexão JDBC até o fim da sincronização), como ator de sistema do tenant; nenhuma
 * exceção escapa para quem confirmou a transação.
 */
@Component
class ProviderCalls {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderCalls.class);

    private final ProviderResolver resolver;
    private final TenantExecution tenantExecution;
    private final CurrentActor currentActor;
    private final JobQueue jobQueue;
    private final TelephonyMetrics metrics;
    private final TaskExecutor executor;

    ProviderCalls(ProviderResolver resolver, TenantExecution tenantExecution, CurrentActor currentActor,
                  JobQueue jobQueue, TelephonyMetrics metrics,
                  @Qualifier("telephonyCommandExecutor") TaskExecutor executor) {
        this.resolver = resolver;
        this.tenantExecution = tenantExecution;
        this.currentActor = currentActor;
        this.jobQueue = jobQueue;
        this.metrics = metrics;
        this.executor = executor;
    }

    /**
     * Executa {@code action} depois do commit da transação corrente (obrigatória), no executor de comandos e no
     * tenant corrente. Exceções são registradas no log técnico e não propagam.
     */
    void afterCommit(String operation, UUID callId, Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Comando ao provedor sem transação ativa: " + operation);
        }
        UUID tenantId = currentActor.tenantId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executor.execute(() -> tenantExecution.withTenantContext(tenantId, "telephony." + operation, () -> {
                    try {
                        action.run();
                    } catch (RuntimeException e) {
                        LOG.atError().addKeyValue("callId", callId).addKeyValue("operation", operation).setCause(e)
                                .log("Falha no comando ao provedor depois do commit");
                    }
                }));
            }
        });
    }

    /** Pede o encerramento ao provedor depois do commit (o estado local já foi decidido na transação). */
    void hangupAfterCommit(TelephonyAccount account, Call call, HangupReason reason) {
        hangupAfterCommit(account, call.getId(), call.getProviderCallId(), call.getProviderLegId(), reason);
    }

    /** Idem, pelos ids (aceite do provedor que não pôde ser gravado na chamada). */
    void hangupAfterCommit(TelephonyAccount account, UUID callId, String providerCallId, String legId,
                           HangupReason reason) {
        if (providerCallId == null) {
            // pedido ainda sem resposta: a fase de resultado da discagem encerra no provedor ao receber o id
            metrics.providerCommand(name(account), "hangup", "skipped");
            return;
        }
        HangupRequest request = new HangupRequest(resolver.context(account), callId, providerCallId, reason, legId);
        ProviderLine line = line(account);
        afterCommit("hangup", callId, () -> hangup(line, request, true));
    }

    /**
     * Encerramento no provedor, fora de transação.
     *
     * @param retryOnFailure falha transitória agenda o job {@code telephony.hangup}; {@code false} relança
     *                       (a própria execução do job é a retentativa)
     */
    void hangup(ProviderLine line, HangupRequest request, boolean retryOnFailure) {
        if (line.adapter().isEmpty()) {
            metrics.providerCommand(line.name(), "hangup", "skipped");
            return;
        }
        try {
            line.adapter().get().hangup(request);
            metrics.providerCommand(line.name(), "hangup", "success");
        } catch (ProviderRejectedException e) {
            // a chamada já terminou no provedor: o estado local já é final
            metrics.providerCommand(line.name(), "hangup", "rejected");
            LOG.atWarn().addKeyValue("callId", request.callId()).addKeyValue("code", e.code())
                    .log("Provedor recusou o encerramento da chamada");
        } catch (TransientProviderException e) {
            metrics.providerCommand(line.name(), "hangup", "transient");
            if (!retryOnFailure) {
                throw e;
            }
            LOG.atWarn().addKeyValue("callId", request.callId()).addKeyValue("reason", request.reason())
                    .log("Falha transitória ao encerrar a chamada no provedor; nova tentativa pelo job");
            UUID tenantId = currentActor.tenantId();
            tenantExecution.inTenant(tenantId, "telephony.hangup_retry", () -> jobQueue.enqueue(ProviderHangupJob.TYPE,
                    new ProviderHangupJob.Payload(request.callId(), request.reason().name()), null,
                    ProviderHangupJob.key(request.callId())));
        }
    }

    /** Conexão ao operador, fora de transação. @return o provedor aceitou a conexão */
    boolean bridge(ProviderLine line, BridgeRequest request) {
        if (line.adapter().isEmpty()) {
            metrics.providerCommand(line.name(), "bridge", "skipped");
            return false;
        }
        try {
            line.adapter().get().bridge(request);
            metrics.providerCommand(line.name(), "bridge", "success");
            return true;
        } catch (TransientProviderException | ProviderRejectedException e) {
            metrics.providerCommand(line.name(), "bridge",
                    e instanceof ProviderRejectedException ? "rejected" : "transient");
            LOG.atWarn().addKeyValue("callId", request.callId()).addKeyValue("provider", line.name())
                    .log("Falha ao conectar a chamada ao operador");
            return false;
        }
    }

    ProviderLine line(TelephonyAccount account) {
        return new ProviderLine(name(account), resolver.provider(account.getProvider()));
    }

    private static String name(TelephonyAccount account) {
        return account.getProvider().name().toLowerCase(Locale.ROOT);
    }

    /** Adaptador da conta resolvido na transação (vazio: provedor indisponível neste ambiente). */
    record ProviderLine(String name, Optional<TelephonyProvider> adapter) {
    }
}
