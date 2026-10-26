package br.com.gestorcobranca.pendingwork.application.service;

import br.com.gestorcobranca.pendingwork.application.PendingWorkProperties;
import br.com.gestorcobranca.pendingwork.application.port.out.PendingWorkObservationStore;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/** Retenção do registro diário de observações (uma instância por vez, por assessoria ativa). */
@Component
class PendingWorkJobs {

    private static final Logger LOG = LoggerFactory.getLogger(PendingWorkJobs.class);

    private final TenantDirectory tenants;
    private final TenantExecution tenantExecution;
    private final PendingWorkObservationStore store;
    private final PendingWorkMetrics metrics;
    private final PendingWorkProperties properties;
    private final Clock clock;

    PendingWorkJobs(TenantDirectory tenants, TenantExecution tenantExecution, PendingWorkObservationStore store,
                    PendingWorkMetrics metrics, PendingWorkProperties properties, Clock clock) {
        this.tenants = tenants;
        this.tenantExecution = tenantExecution;
        this.store = store;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.pending-work.retention-cron:0 40 4 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "pendingwork.retention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    void purgeObservations() {
        LocalDate before = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE))
                .minusDays(properties.observationRetentionDays());
        for (UUID tenantId : tenants.activeTenantIds()) {
            try {
                int purged = tenantExecution.callInTenant(tenantId, "pendingwork.retention", () -> store.purgeBefore(before));
                metrics.retentionPurged(purged);
            } catch (RuntimeException e) {
                metrics.jobFailure("retention");
                LOG.atError().addKeyValue("tenantId", tenantId).setCause(e)
                        .log("Falha na retenção das observações de pendências");
            }
        }
    }
}
