package br.com.gestorcobranca.analytics.application.service;

import br.com.gestorcobranca.analytics.application.AnalyticsProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Métricas técnicas do módulo ({@code analytics.*}, namespace CloudWatch {@code GestorCobranca}):
 * duração das consultas e das atualizações, consultas lentas, exportações e falhas. Só contagens e
 * durações; tags de cardinalidade fixa (relatório, conjunto, formato, resultado).
 */
@Component
class AnalyticsMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(AnalyticsMetrics.class);

    private final MeterRegistry registry;
    private final Duration slowThreshold;

    AnalyticsMetrics(MeterRegistry registry, AnalyticsProperties properties) {
        this.registry = registry;
        this.slowThreshold = properties.slowQueryThreshold();
    }

    /** Mede a consulta de um painel; acima do limite conta como lenta e registra WARN com o agrupamento. */
    <T> T query(String report, String groupBy, Supplier<T> query) {
        long start = System.nanoTime();
        String outcome = "error";
        try {
            T result = query.get();
            outcome = "ok";
            return result;
        } finally {
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            Timer.builder("analytics.query.duration").tag("report", report).tag("outcome", outcome)
                    .publishPercentiles(0.5, 0.95, 0.99).register(registry).record(elapsed);
            if (elapsed.compareTo(slowThreshold) > 0) {
                Counter.builder("analytics.queries.slow").tag("report", report).register(registry).increment();
                LOG.atWarn().addKeyValue("report", report).addKeyValue("groupBy", groupBy)
                        .addKeyValue("durationMs", elapsed.toMillis()).log("Consulta de painel lenta");
            }
        }
    }

    void refreshed(String dataset, Duration duration, long rows) {
        Timer.builder("analytics.refresh.duration").tag("dataset", dataset).tag("outcome", "ok")
                .publishPercentiles(0.5, 0.95).register(registry).record(duration);
        Counter.builder("analytics.refresh.rows").tag("dataset", dataset).register(registry).increment(rows);
    }

    void refreshCycle(String trigger, Duration duration, int tenants, int failures) {
        Timer.builder("analytics.refresh.cycle.duration").tag("trigger", trigger).register(registry).record(duration);
        Counter.builder("analytics.refresh.tenants").tag("trigger", trigger).tag("outcome", "ok").register(registry)
                .increment(tenants - failures);
        if (failures > 0) {
            Counter.builder("analytics.refresh.tenants").tag("trigger", trigger).tag("outcome", "failed")
                    .register(registry).increment(failures);
        }
    }

    void refreshFailure(String trigger) {
        Counter.builder("analytics.refresh.failures").tag("trigger", trigger).register(registry).increment();
    }

    void exported(String report, String format, int rows) {
        Counter.builder("analytics.exports").tag("report", report).tag("format", format).register(registry).increment();
        Counter.builder("analytics.export.rows").tag("report", report).register(registry).increment(rows);
    }
}
