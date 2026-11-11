package br.com.gestorcobranca.portal.application.service;

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
 * Jobs do portal (uma instância por vez, por assessoria ativa): expiração das sessões vencidas e retenção dos
 * dados de acesso (desafios, contadores do bloqueio progressivo e IP/agente das sessões encerradas).
 */
@Component
class PortalJobs {

    private static final Logger LOG = LoggerFactory.getLogger(PortalJobs.class);
    private static final String JOB = "portal.session_expiry";
    private static final String RETENTION_JOB = "portal.retention";
    /** Teto de rodadas por assessoria e execução (cada rodada na própria transação). */
    static final int MAX_RETENTION_ROUNDS = 50;

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

    @Scheduled(cron = "${app.portal.retention-cron:0 20 3 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = RETENTION_JOB, lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    void purgeAccessData() {
        for (UUID tenantId : tenants.activeTenantIds()) {
            purgeAccessData(tenantId);
        }
    }

    /** @return registros descartados ou limpos na assessoria */
    int purgeAccessData(UUID tenantId) {
        int total = 0;
        PortalMaintenance.AccessRetention retention = null;
        try {
            // prazos da assessoria (tela de Retenção) ou o padrão do sistema, lidos uma vez por execução
            PortalMaintenance.AccessRetention r = tenantExecution.callInTenant(tenantId, RETENTION_JOB,
                    maintenance::accessRetention);
            retention = r;
            for (int round = 0; round < MAX_RETENTION_ROUNDS; round++) {
                PortalMaintenance.Purged purged = tenantExecution.callInTenant(tenantId, RETENTION_JOB,
                        () -> maintenance.purgeAccessData(r));
                total += purged.total();
                if (!purged.full()) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            metrics.jobFailure(RETENTION_JOB);
            LOG.atError().addKeyValue("tenantId", tenantId).addKeyValue("job", RETENTION_JOB).setCause(e)
                    .log("Falha no descarte dos dados de acesso do portal");
        }
        if (total > 0) {
            // origem e prazo de cada categoria (TENANT = configurado pela assessoria; SYSTEM_DEFAULT = padrão)
            LOG.atInfo().addKeyValue("tenantId", tenantId).addKeyValue("job", RETENTION_JOB).addKeyValue("purged", total)
                    .addKeyValue("challengesDays", retention.challenges().amount())
                    .addKeyValue("challengesSource", retention.challenges().source())
                    .addKeyValue("throttlesDays", retention.throttles().amount())
                    .addKeyValue("throttlesSource", retention.throttles().source())
                    .addKeyValue("sessionClientDataDays", retention.sessionClientData().amount())
                    .addKeyValue("sessionClientDataSource", retention.sessionClientData().source())
                    .log("Retenção dos dados de acesso do portal executada");
        }
        return total;
    }
}
