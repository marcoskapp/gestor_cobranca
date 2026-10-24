package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases;
import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases.RetentionPolicyView;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.out.WebhookReceiptLog;
import br.com.gestorcobranca.telephony.domain.model.Agent;
import br.com.gestorcobranca.telephony.domain.model.Call;
import br.com.gestorcobranca.telephony.domain.model.CallRecording;
import br.com.gestorcobranca.telephony.domain.model.CallStatus;
import br.com.gestorcobranca.telephony.domain.model.DialerCampaign;
import br.com.gestorcobranca.telephony.domain.model.DialerCampaignStatus;
import br.com.gestorcobranca.telephony.domain.model.HangupSide;
import br.com.gestorcobranca.telephony.domain.model.PresenceStatus;
import br.com.gestorcobranca.telephony.domain.repository.AgentRepository;
import br.com.gestorcobranca.telephony.domain.repository.CallRecordingRepository;
import br.com.gestorcobranca.telephony.domain.repository.CallRepository;
import br.com.gestorcobranca.telephony.domain.repository.DialerCampaignRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manutenção por assessoria (no tenant corrente):
 * <ul>
 *   <li>descarte do arquivo das gravações pela política {@code CALL_RECORDINGS} do compliance
 *       (metadados permanecem);</li>
 *   <li>limpeza dos recibos e eventos de webhook fora da janela de replay;</li>
 *   <li>saída automática de operadores sem sinal do cliente (exceto em chamada);</li>
 *   <li>conciliação de chamadas sem evento do provedor há mais que o limite (encerradas como
 *       {@code FAILED/STALE}).</li>
 * </ul>
 */
@Component
class TelephonyMaintenance {

    static final String RETENTION_CATEGORY = "CALL_RECORDINGS";
    private static final int SWEEP_BATCH = 200;

    private final CallRecordingRepository recordings;
    private final CallRepository calls;
    private final AgentRepository agents;
    private final DialerCampaignRepository campaigns;
    private final WebhookReceiptLog receipts;
    private final RetentionUseCases retention;
    private final FileStorage storage;
    private final PresenceRecorder presence;
    private final CallLifecycle lifecycle;
    private final DialerTicks ticks;
    private final TelephonyMetrics metrics;
    private final TelephonyProperties properties;
    private final Clock clock;

    TelephonyMaintenance(CallRecordingRepository recordings, CallRepository calls, AgentRepository agents,
                         DialerCampaignRepository campaigns, WebhookReceiptLog receipts, RetentionUseCases retention,
                         FileStorage storage, PresenceRecorder presence, CallLifecycle lifecycle, DialerTicks ticks,
                         TelephonyMetrics metrics, TelephonyProperties properties, Clock clock) {
        this.recordings = recordings;
        this.calls = calls;
        this.agents = agents;
        this.campaigns = campaigns;
        this.receipts = receipts;
        this.retention = retention;
        this.storage = storage;
        this.presence = presence;
        this.lifecycle = lifecycle;
        this.ticks = ticks;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return gravações tratadas neste lote (lote cheio = há mais) */
    @Transactional
    public int purgeRecordings() {
        Optional<RetentionPolicyView> policy = retention.policyFor(RETENTION_CATEGORY).filter(RetentionPolicyView::enabled);
        if (policy.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now(clock);
        Instant cutoff = ZonedDateTime.ofInstant(now, TimeConfig.BUSINESS_ZONE)
                .minusMonths(policy.get().retentionMonths()).toInstant();
        List<CallRecording> batch = recordings.findStoredBefore(cutoff, properties.retentionBatch());
        for (CallRecording recording : batch) {
            purge(recording, "RETENTION", now);
            metrics.recording("purged_retention");
        }
        return batch.size();
    }

    void purge(CallRecording recording, String reason, Instant now) {
        String key = recording.purge(reason, now);
        recordings.save(recording);
        if (key != null) {
            storage.delete(key);
        }
    }

    @Transactional
    public int purgeWebhookReceipts() {
        return receipts.purgeBefore(Instant.now(clock).minus(properties.webhook().maxEventAge()));
    }

    /** Operadores sem sinal do cliente além do limite saem (em chamada, aguardam o fim da chamada). */
    @Transactional
    public int disconnectSilentAgents() {
        Instant now = Instant.now(clock);
        List<UUID> ids = agents.findLoggedInWithoutHeartbeatSince(now.minus(properties.presenceTimeout()), SWEEP_BATCH);
        int count = 0;
        for (Agent agent : agents.findAllByIdIn(ids)) {
            Agent locked = agents.findByUserIdForUpdate(agent.getUserId()).orElseThrow();
            if (locked.getStatus() == PresenceStatus.ON_CALL || !locked.heartbeatExpired(now, properties.presenceTimeout())) {
                continue;
            }
            presence.record(locked.logout(true, now), "timeout");
            agents.save(locked);
            count++;
        }
        metrics.reconciled("agent_timeout", count);
        return count;
    }

    /** Chamadas sem evento do provedor além do limite são encerradas (o provedor não confirmou o fim). */
    @Transactional
    public int closeStaleCalls() {
        Instant now = Instant.now(clock);
        List<UUID> ids = calls.findStaleActiveIds(now.minus(properties.staleCallAfter()), SWEEP_BATCH);
        int count = 0;
        for (UUID id : ids) {
            Call call = calls.findByIdForUpdate(id).orElseThrow();
            if (call.getStatus().isTerminal()) {
                continue;
            }
            boolean ended = call.getStatus() == CallStatus.QUEUED
                    ? endQueued(call, now)
                    : call.end(CallStatus.FAILED, now, HangupSide.SYSTEM, "STALE");
            if (ended) {
                lifecycle.ended(call);
                count++;
            }
        }
        metrics.reconciled("stale_call", count);
        return count;
    }

    private boolean endQueued(Call call, Instant now) {
        call.failBeforeDialing("STALE", "Discagem não concluída", now);
        return true;
    }

    /** Campanhas ativas que precisam de um ciclo do discador. */
    @Transactional
    public int requestTicks() {
        List<DialerCampaign> active = campaigns.findAllByStatus(DialerCampaignStatus.ACTIVE);
        active.forEach(c -> ticks.requestNow(c.getId()));
        return active.size();
    }
}
