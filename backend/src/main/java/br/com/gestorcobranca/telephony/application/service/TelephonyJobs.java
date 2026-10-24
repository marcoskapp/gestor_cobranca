package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Agendamentos do módulo (uma instância por vez, via ShedLock), percorrendo as assessorias ativas.
 * A falha numa assessoria é registrada (alarme {@code telephony.jobs.failures}) e não impede as demais.
 */
@Component
class TelephonyJobs {

    private static final Logger LOG = LoggerFactory.getLogger(TelephonyJobs.class);
    private static final int MAX_BATCHES = 200;

    private final TenantDirectory tenantDirectory;
    private final TenantExecution tenantExecution;
    private final TelephonyMaintenance maintenance;
    private final TelephonyMetrics metrics;
    private final TelephonyProperties properties;

    TelephonyJobs(TenantDirectory tenantDirectory, TenantExecution tenantExecution, TelephonyMaintenance maintenance,
                  TelephonyMetrics metrics, TelephonyProperties properties) {
        this.tenantDirectory = tenantDirectory;
        this.tenantExecution = tenantExecution;
        this.maintenance = maintenance;
        this.metrics = metrics;
        this.properties = properties;
    }

    /** Ciclo do discador para as campanhas ativas (o job de cada campanha é único enquanto pendente). */
    @Scheduled(fixedDelayString = "${app.telephony.dialer.tick-interval:PT3S}")
    @SchedulerLock(name = "telephony.dialer_heartbeat", lockAtMostFor = "PT1M")
    void dialerHeartbeat() {
        forEachTenant("dialer_heartbeat", maintenance::requestTicks);
    }

    /** Saída de operadores sem sinal e conciliação de chamadas sem evento do provedor. */
    @Scheduled(fixedDelayString = "PT30S")
    @SchedulerLock(name = "telephony.reconciliation", lockAtMostFor = "PT5M")
    void reconciliation() {
        forEachTenant("agent_timeout", maintenance::disconnectSilentAgents);
        forEachTenant("stale_calls", maintenance::closeStaleCalls);
    }

    @Scheduled(cron = "${app.telephony.retention-cron:0 50 2 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "telephony.retention", lockAtMostFor = "PT4H", lockAtLeastFor = "PT1M")
    void retention() {
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            try {
                int batches = 0;
                int processed;
                do {
                    processed = tenantExecution.callInTenant(tenantId, "telephony.retention", maintenance::purgeRecordings);
                    batches++;
                } while (processed == properties.retentionBatch() && batches < MAX_BATCHES);
                tenantExecution.callInTenant(tenantId, "telephony.webhook_receipts", maintenance::purgeWebhookReceipts);
            } catch (RuntimeException e) {
                metrics.jobFailure("retention");
                LOG.atError().addKeyValue("tenantId", tenantId).setCause(e).log("Falha no descarte de gravações");
            }
        }
    }

    private void forEachTenant(String job, Supplier<Integer> action) {
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            try {
                tenantExecution.callInTenant(tenantId, "telephony." + job, action);
            } catch (RuntimeException e) {
                metrics.jobFailure(job);
                LOG.atError().addKeyValue("tenantId", tenantId).addKeyValue("job", job).setCause(e)
                        .log("Falha no agendamento da telefonia");
            }
        }
    }
}
