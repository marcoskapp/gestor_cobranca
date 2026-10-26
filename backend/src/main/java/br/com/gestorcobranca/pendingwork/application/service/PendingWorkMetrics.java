package br.com.gestorcobranca.pendingwork.application.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Métricas de "Minhas pendências" (docs/tecnico/logs-e-metricas.md §5). Tags de cardinalidade fixa
 * (id da fonte, desfecho); nunca usuário.
 */
@Component
class PendingWorkMetrics {

    private final MeterRegistry registry;

    PendingWorkMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** @param outcome {@code complete} (todas as fontes responderam) ou {@code partial} */
    void request(String outcome, Duration duration, int sources) {
        Timer.builder("pendingwork.request.duration").tag("outcome", outcome).publishPercentiles(0.5, 0.95, 0.99)
                .register(registry).record(duration);
        registry.summary("pendingwork.request.sources").record(sources);
    }

    /** @param outcome {@code ok}, {@code error}, {@code timeout} ou {@code busy} */
    void source(String source, String outcome, Duration duration) {
        Timer.builder("pendingwork.source.duration").tag("source", source).tag("outcome", outcome)
                .publishPercentiles(0.5, 0.95, 0.99).register(registry).record(duration);
    }

    /** @param outcome {@code recorded}, {@code already_recorded} ou {@code failed} */
    void observation(String outcome) {
        registry.counter("pendingwork.observations", "outcome", outcome).increment();
    }

    void retentionPurged(int rows) {
        registry.counter("pendingwork.retention.purged").increment(rows);
    }

    void jobFailure(String job) {
        registry.counter("pendingwork.jobs.failures", "job", job).increment();
    }
}
