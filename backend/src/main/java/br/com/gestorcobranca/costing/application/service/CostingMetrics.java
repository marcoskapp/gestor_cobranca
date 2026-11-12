package br.com.gestorcobranca.costing.application.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Métricas técnicas do costing (docs/tecnico/logs-e-metricas.md §5). Nenhum valor de custo nem de pessoa vira métrica:
 * só durações, contagens de linhas e resultados.
 */
@Component
class CostingMetrics {

    private final MeterRegistry registry;

    CostingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void refreshed(String trigger, String outcome, Duration elapsed, long rows) {
        Timer.builder("costing.refresh.duration").tag("trigger", trigger).tag("outcome", outcome)
                .publishPercentiles(0.5, 0.95).register(registry).record(elapsed);
        if ("ok".equals(outcome)) {
            DistributionSummary.builder("costing.refresh.rows").tag("trigger", trigger).register(registry).record(rows);
        }
    }

    void refreshFailure(String trigger) {
        Counter.builder("costing.refresh.failures").tag("trigger", trigger).register(registry).increment();
    }

    void queried(String outcome, Duration elapsed) {
        Timer.builder("costing.margin.query.duration").tag("outcome", outcome).publishPercentiles(0.5, 0.95, 0.99)
                .register(registry).record(elapsed);
    }

    void exported(String format, int rows) {
        Counter.builder("costing.exports").tag("format", format).register(registry).increment();
        DistributionSummary.builder("costing.export.rows").register(registry).record(rows);
    }
}
