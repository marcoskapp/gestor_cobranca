package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.jobs.JobQueue;
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
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Comandos à chamada em andamento no provedor ({@code bridge} e {@code hangup}) como <b>jobs duráveis</b> gravados na
 * mesma transação que decidiu o estado local (decisão do responsável à 884): o comando existe se, e só se, o estado
 * local foi confirmado — transação desfeita, nenhum comando; instância que cai entre o commit e o envio, o comando
 * fica na fila. Depois do commit a própria instância executa o job na hora ({@link JobQueue#enqueueAndDispatch},
 * reivindicando-o como o worker: nunca roda duas vezes); sem essa execução, o ciclo normal do worker o executa.
 * <ul>
 *   <li>encerramento ({@link ProviderHangupJob}): falha transitória → nova tentativa pelo próprio job; recusa (a
 *       chamada já terminou no provedor) → concluído, só registrado;</li>
 *   <li>conexão ao operador ({@link ProviderBridgeJob}): sem nova tentativa; o provedor não conectando, a conexão é
 *       desfeita em transação própria.</li>
 * </ul>
 * O HTTP ao provedor roda na thread do job, sem transação e sem o lock da chamada.
 */
@Component
class ProviderCalls {

    private static final Logger LOG = LoggerFactory.getLogger(ProviderCalls.class);

    private final ProviderResolver resolver;
    private final JobQueue jobQueue;
    private final TelephonyMetrics metrics;

    ProviderCalls(ProviderResolver resolver, JobQueue jobQueue, TelephonyMetrics metrics) {
        this.resolver = resolver;
        this.jobQueue = jobQueue;
        this.metrics = metrics;
    }

    /** Grava o encerramento no provedor (transação corrente, obrigatória); executado logo após o commit. */
    void hangup(TelephonyAccount account, Call call, HangupReason reason) {
        hangup(account, call.getId(), call.getProviderCallId(), call.getProviderLegId(), reason);
    }

    /** Idem, pelos ids (aceite do provedor que não pôde ser gravado na chamada). */
    void hangup(TelephonyAccount account, UUID callId, String providerCallId, String legId, HangupReason reason) {
        if (providerCallId == null) {
            // pedido ainda sem resposta: a fase de resultado da discagem encerra no provedor ao receber o id
            metrics.providerCommand(name(account), "hangup", "skipped");
            return;
        }
        jobQueue.enqueueAndDispatch(ProviderHangupJob.TYPE, new ProviderHangupJob.Payload(callId, reason.name(),
                account.getId(), providerCallId, legId), ProviderHangupJob.key(callId, providerCallId));
    }

    /** Grava a conexão da chamada atendida ao operador (transação corrente, obrigatória); executada logo após o commit. */
    void bridge(TelephonyAccount account, Call call, UUID operatorId) {
        jobQueue.enqueueAndDispatch(ProviderBridgeJob.TYPE, new ProviderBridgeJob.Payload(call.getId(), account.getId(),
                operatorId), ProviderBridgeJob.key(call.getId()));
    }

    /**
     * Encerramento no provedor, fora de transação (thread do job). Recusa → a chamada já terminou lá: concluído.
     *
     * @throws TransientProviderException falha transitória (o worker tenta de novo)
     */
    void sendHangup(ProviderLine line, HangupRequest request) {
        if (line.adapter().isEmpty()) {
            metrics.providerCommand(line.name(), "hangup", "skipped");
            return;
        }
        try {
            line.adapter().get().hangup(request);
            metrics.providerCommand(line.name(), "hangup", "success");
        } catch (ProviderRejectedException e) {
            // a chamada já terminou no provedor (ou não existe mais lá): o estado local já é final, nada a repetir
            metrics.providerCommand(line.name(), "hangup", "rejected");
            LOG.atWarn().addKeyValue("callId", request.callId()).addKeyValue("code", e.code())
                    .log("Provedor recusou o encerramento da chamada");
        } catch (TransientProviderException e) {
            metrics.providerCommand(line.name(), "hangup", "transient");
            LOG.atWarn().addKeyValue("callId", request.callId()).addKeyValue("reason", request.reason())
                    .log("Falha transitória ao encerrar a chamada no provedor; nova tentativa pelo job");
            throw e;
        }
    }

    /** Conexão ao operador, fora de transação (thread do job). @return o provedor aceitou a conexão */
    boolean sendBridge(ProviderLine line, BridgeRequest request) {
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

    static String name(TelephonyAccount account) {
        return account.getProvider().name().toLowerCase(Locale.ROOT);
    }

    /** Adaptador da conta resolvido na transação (vazio: provedor indisponível neste ambiente). */
    record ProviderLine(String name, Optional<TelephonyProvider> adapter) {
    }
}
