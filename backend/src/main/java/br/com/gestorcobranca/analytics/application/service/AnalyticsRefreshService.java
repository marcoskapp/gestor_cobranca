package br.com.gestorcobranca.analytics.application.service;

import br.com.gestorcobranca.analytics.application.AnalyticsProperties;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.DatasetFreshness;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsRefreshUseCases;
import br.com.gestorcobranca.analytics.application.port.out.AnalyticsReader;
import br.com.gestorcobranca.analytics.application.port.out.SnapshotStore;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.security.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Reconstrói os snapshots da assessoria corrente numa única transação (os quatro conjuntos ficam
 * coerentes entre si) e grava o carimbo "dados até" = instante em que a leitura começou.
 */
@Service
@Transactional
class AnalyticsRefreshService implements AnalyticsRefreshUseCases {

    /** Ordem fixa: a safra lê os recebimentos da mesma fonte; posições ao final. */
    static final List<String> DATASETS = List.of("recovery_daily", "agreement_cohort", "portfolio_position",
            "case_position");

    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsRefreshService.class);

    private final SnapshotStore store;
    private final AnalyticsReader reader;
    private final AnalyticsAccess access;
    private final AnalyticsMetrics metrics;
    private final AnalyticsProperties properties;
    private final Clock clock;

    AnalyticsRefreshService(SnapshotStore store, AnalyticsReader reader, AnalyticsAccess access, AnalyticsMetrics metrics,
                            AnalyticsProperties properties, Clock clock) {
        this.store = store;
        this.reader = reader;
        this.access = access;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public List<DatasetFreshness> refreshNow() {
        access.requireAny("analytics_refresh", Permission.DASHBOARD_VIEW, Permission.REPORT_VIEW);
        store.lockForRefresh(properties.refreshStatementTimeout());
        Optional<Instant> last = store.lastRefreshedAt();
        Instant now = Instant.now(clock);
        Guard.requireState(last.isEmpty() || !last.get().plus(properties.manualRefreshMinInterval()).isAfter(now),
                "analytics.refresh_too_soon", "Os painéis foram atualizados há pouco; tente novamente em alguns minutos");
        refreshLocked("manual");
        return reader.freshness().stream().map(f -> new DatasetFreshness(f.dataset(), f.dataUntil(), f.refreshedAt(),
                f.durationMs(), f.rowCount())).toList();
    }

    /** Atualização agendada (chamada pelo job dentro da transação da assessoria). */
    long refreshCurrentTenant() {
        store.lockForRefresh(properties.refreshStatementTimeout());
        return refreshLocked("scheduled");
    }

    private long refreshLocked(String trigger) {
        LocalDate reference = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        long total = 0;
        for (String dataset : DATASETS) {
            Instant dataUntil = Instant.now(clock);
            long start = System.nanoTime();
            long rows = store.refresh(dataset, reference, TimeConfig.BUSINESS_ZONE_ID);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            store.saveState(dataset, dataUntil, Instant.now(clock), elapsed.toMillis(), rows);
            metrics.refreshed(dataset, elapsed, rows);
            total += rows;
            LOG.atDebug().addKeyValue("dataset", dataset).addKeyValue("rows", rows)
                    .addKeyValue("durationMs", elapsed.toMillis()).addKeyValue("trigger", trigger)
                    .log("Snapshot do analytics reconstruído");
        }
        return total;
    }
}
