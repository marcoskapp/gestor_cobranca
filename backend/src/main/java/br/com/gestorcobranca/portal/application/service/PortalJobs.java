package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Expiração das sessões do portal vencidas (uma instância por vez, por assessoria ativa). */
@Component
class PortalJobs {

    private static final Logger LOG = LoggerFactory.getLogger(PortalJobs.class);
    private static final String JOB = "portal.session_expiry";

    private final TenantDirectory tenants;
    private final TenantExecution tenantExecution;
    private final PortalMaintenance maintenance;
    private final PortalMetrics metrics;

    PortalJobs(TenantDirectory tenants, TenantExecution tenantExecution, PortalMaintenance maintenance,
               PortalMetrics metrics) {
        this.tenants = tenants;
        this.tenantExecution = tenantExecution;
        this.maintenance = maintenance;
        this.metrics = metrics;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "${app.portal.session-expiry-interval:PT10M}")
    @SchedulerLock(name = JOB, lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    void expireSessions() {
        for (UUID tenantId : tenants.activeTenantIds()) {
            try {
                int expired;
                do {
                    expired = tenantExecution.callInTenant(tenantId, JOB, maintenance::expireSessions);
                } while (expired == PortalMaintenance.BATCH);
            } catch (RuntimeException e) {
                metrics.jobFailure(JOB);
                LOG.atError().addKeyValue("tenantId", tenantId).addKeyValue("job", JOB).setCause(e)
                        .log("Falha ao expirar sessões do portal");
            }
        }
    }
}
