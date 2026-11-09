package br.com.gestorcobranca.complaint.application.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Métricas técnicas do módulo (docs/tecnico/logs-e-metricas.md §5): só canal, resultado e veredito — nada pessoal. */
@Component
class ComplaintMetrics {

    private final MeterRegistry registry;

    ComplaintMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void registered(String channel) {
        registry.counter("complaint.registered", "channel", channel).increment();
    }

    void answered(String channel, boolean onTime) {
        registry.counter("complaint.answered", "channel", channel, "on_time", String.valueOf(onTime)).increment();
    }

    void closed(String channel, String outcome) {
        registry.counter("complaint.closed", "channel", channel, "outcome", outcome).increment();
    }

    void overdue(String channel) {
        registry.counter("complaint.overdue", "channel", channel).increment();
    }

    void evidenceScan(String engine, String verdict) {
        registry.counter("complaint.evidence.scans", "engine", engine, "verdict", verdict).increment();
    }

    void evidenceScanDuration(String engine, Duration duration) {
        registry.timer("complaint.evidence.scan.duration", "engine", engine).record(duration);
    }
}
