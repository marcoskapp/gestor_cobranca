package br.com.gestorcobranca.complaint.application.service;

import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Agendamentos do módulo (uma instância por vez, via ShedLock), por assessoria ativa. */
@Component
class ComplaintJobs {

    private static final Logger LOG = LoggerFactory.getLogger(ComplaintJobs.class);

    private final TenantDirectory tenantDirectory;
    private final TenantExecution tenantExecution;
    private final ComplaintMaintenance maintenance;

    ComplaintJobs(TenantDirectory tenantDirectory, TenantExecution tenantExecution, ComplaintMaintenance maintenance) {
        this.tenantDirectory = tenantDirectory;
        this.tenantExecution = tenantExecution;
        this.maintenance = maintenance;
    }

    @Scheduled(fixedDelayString = "${app.complaint.overdue-check-interval:PT30M}", initialDelayString = "PT4M")
    @SchedulerLock(name = "complaint.overdue", lockAtMostFor = "PT20M", lockAtLeastFor = "PT30S")
    void flagOverdue() {
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            try {
                int flagged;
                do {
                    flagged = tenantExecution.callInTenant(tenantId, "complaint.overdue", maintenance::flagOverdue);
                } while (flagged == ComplaintMaintenance.BATCH);
            } catch (RuntimeException e) {
                LOG.atError().addKeyValue("tenantId", tenantId).setCause(e)
                        .log("Falha ao verificar prazos de reclamações");
            }
        }
    }
}
