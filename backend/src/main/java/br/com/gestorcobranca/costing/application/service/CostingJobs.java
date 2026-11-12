package br.com.gestorcobranca.costing.application.service;

import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Apuração diária dos custos ({@code costing.refresh}, uma instância por vez via ShedLock): assessoria por assessoria,
 * cada uma em transação própria, reprocessando os últimos dias até ontem; falha numa assessoria é registrada e não
 * impede as demais.
 */
@Component
class CostingJobs {

    private static final Logger LOG = LoggerFactory.getLogger(CostingJobs.class);

    private final TenantDirectory tenants;
    private final TenantExecution tenantExecution;
    private final CostRefreshService refresh;
    private final CostingMetrics metrics;

    CostingJobs(TenantDirectory tenants, TenantExecution tenantExecution, CostRefreshService refresh,
                CostingMetrics metrics) {
        this.tenants = tenants;
        this.tenantExecution = tenantExecution;
        this.refresh = refresh;
        this.metrics = metrics;
    }

    @Scheduled(cron = "${app.costing.refresh-cron:0 30 5 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "costing.refresh", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    void refreshAll() {
        List<UUID> active = tenants.activeTenantIds();
        int failures = 0;
        for (UUID tenantId : active) {
            try {
                tenantExecution.callInTenant(tenantId, "costing.refresh", refresh::refreshScheduled);
            } catch (RuntimeException e) {
                failures++;
                metrics.refreshFailure("scheduled");
                LOG.atError().addKeyValue("tenantId", tenantId).addKeyValue("process", "costing.refresh").setCause(e)
                        .log("Falha ao apurar os custos da assessoria");
            }
        }
        LOG.atInfo().addKeyValue("process", "costing.refresh").addKeyValue("tenants", active.size())
                .addKeyValue("failures", failures).log("Apuração diária dos custos concluída");
    }
}
