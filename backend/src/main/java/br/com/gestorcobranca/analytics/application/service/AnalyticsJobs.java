package br.com.gestorcobranca.analytics.application.service;

import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Atualização periódica dos snapshots (uma instância por vez, via ShedLock), assessoria por
 * assessoria, cada uma em transação própria; falha numa assessoria é registrada e não impede as demais.
 */
@Component
class AnalyticsJobs {

    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsJobs.class);

    private final TenantDirectory tenants;
    private final TenantExecution tenantExecution;
    private final AnalyticsRefreshService refresh;
    private final AnalyticsMetrics metrics;

    AnalyticsJobs(TenantDirectory tenants, TenantExecution tenantExecution, AnalyticsRefreshService refresh,
                  AnalyticsMetrics metrics) {
        this.tenants = tenants;
        this.tenantExecution = tenantExecution;
        this.refresh = refresh;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${app.analytics.refresh-interval:PT15M}",
            initialDelayString = "${app.analytics.refresh-initial-delay:PT1M}")
    @SchedulerLock(name = "analytics.refresh", lockAtMostFor = "PT50M", lockAtLeastFor = "PT1M")
    void refreshAll() {
        long start = System.nanoTime();
        List<UUID> active = tenants.activeTenantIds();
        int failures = 0;
        long rows = 0;
        for (UUID tenantId : active) {
            try {
                rows += tenantExecution.callInTenant(tenantId, "analytics.refresh", refresh::refreshCurrentTenant);
            } catch (RuntimeException e) {
                failures++;
                metrics.refreshFailure("scheduled");
                LOG.atError().addKeyValue("tenantId", tenantId).addKeyValue("process", "analytics.refresh").setCause(e)
                        .log("Falha ao atualizar os painéis da assessoria");
            }
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        metrics.refreshCycle("scheduled", elapsed, active.size(), failures);
        LOG.atInfo().addKeyValue("tenants", active.size()).addKeyValue("failures", failures).addKeyValue("rows", rows)
                .addKeyValue("durationMs", elapsed.toMillis()).log("Atualização dos painéis concluída");
    }
}
