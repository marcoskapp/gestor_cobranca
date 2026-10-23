package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Agendamentos do módulo (uma instância por vez, via ShedLock), percorrendo as assessorias
 * ativas. A falha num tenant é registrada e não impede os demais.
 */
@Component
class ComplianceJobs {

    private static final Logger LOG = LoggerFactory.getLogger(ComplianceJobs.class);
    private static final int BATCH = 500;

    private final TenantDirectory tenantDirectory;
    private final TenantExecution tenantExecution;
    private final ComplianceMaintenance maintenance;
    private final RetentionService retention;

    ComplianceJobs(TenantDirectory tenantDirectory, TenantExecution tenantExecution, ComplianceMaintenance maintenance,
                   RetentionService retention) {
        this.tenantDirectory = tenantDirectory;
        this.tenantExecution = tenantExecution;
        this.maintenance = maintenance;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${app.compliance.data-subject-request.overdue-check-interval:PT30M}",
            initialDelayString = "PT3M")
    @SchedulerLock(name = "compliance.dsr-overdue", lockAtMostFor = "PT20M", lockAtLeastFor = "PT30S")
    void flagOverdueRequests() {
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            try {
                int flagged;
                do {
                    flagged = tenantExecution.callInTenant(tenantId, "compliance.dsr_overdue", maintenance::flagOverdueRequests);
                } while (flagged == BATCH);
            } catch (RuntimeException e) {
                LOG.atError().addKeyValue("tenantId", tenantId).setCause(e)
                        .log("Falha ao verificar prazos de solicitações de titulares");
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.compliance.retention.expiry-check-interval:PT5M}", initialDelayString = "PT2M")
    @SchedulerLock(name = "compliance.restriction-expiry", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    void notifyExpiredRestrictions() {
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            try {
                int notified;
                do {
                    notified = tenantExecution.callInTenant(tenantId, "compliance.restriction_expiry",
                            maintenance::notifyExpiredRestrictions);
                } while (notified == BATCH);
            } catch (RuntimeException e) {
                LOG.atError().addKeyValue("tenantId", tenantId).setCause(e)
                        .log("Falha ao comunicar fim de vigência de restrições de contato");
            }
        }
    }

    /** Retenção diária de madrugada (fuso de operação), só nas assessorias com política habilitada. */
    @Scheduled(cron = "${app.compliance.retention.cron:0 30 2 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "compliance.retention", lockAtMostFor = "PT6H", lockAtLeastFor = "PT1M")
    void runRetention() {
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            try {
                tenantExecution.withTenantContext(tenantId, "compliance.retention", retention::runScheduled);
            } catch (RuntimeException e) {
                LOG.atError().addKeyValue("tenantId", tenantId).setCause(e).log("Falha na execução da política de retenção");
            }
        }
    }
}
