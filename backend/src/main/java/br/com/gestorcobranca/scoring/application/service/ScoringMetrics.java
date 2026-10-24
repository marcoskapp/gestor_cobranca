package br.com.gestorcobranca.scoring.application.service;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Métricas {@code scoring.*} (catálogo em {@code docs/tecnico/logs-e-metricas.md} §5). */
@Component
class ScoringMetrics {

    private final MeterRegistry registry;

    ScoringMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Casos escorados por gatilho, tipo de modelo e faixa. */
    void scored(String trigger, String modelKind, String band) {
        registry.counter("scoring.cases.scored", "trigger", trigger, "model", modelKind, "band", band).increment();
    }

    /** Casos não escorados por motivo ({@code legal}, {@code anonymized}, {@code error}). */
    void skipped(String reason) {
        registry.counter("scoring.cases.skipped", "reason", reason).increment();
    }

    /** {@code CaseScoreChanged} publicados por motivo ({@code first}, {@code band_change}, {@code delta}). */
    void published(String reason) {
        registry.counter("scoring.events.published", "reason", reason).increment();
    }

    void batchPage(Duration duration, int cases) {
        Timer.builder("scoring.batch.page.duration").publishPercentiles(0.5, 0.95, 0.99).register(registry)
                .record(duration);
        DistributionSummary.builder("scoring.batch.page.cases").register(registry).record(cases);
    }

    void batchFinished(String status, Duration duration) {
        Timer.builder("scoring.batch.duration").tag("status", status).register(registry).record(duration);
    }

    /** Escoragem não iniciada ({@code legal_basis_missing}, {@code already_exists}). */
    void batchSkipped(String reason) {
        registry.counter("scoring.batch.skipped", "reason", reason).increment();
    }

    void onDemand(String outcome, Duration duration) {
        Timer.builder("scoring.on_demand.duration").tag("outcome", outcome).publishPercentiles(0.5, 0.95, 0.99)
                .register(registry).record(duration);
    }

    void training(String outcome, Duration duration) {
        Timer.builder("scoring.training.duration").tag("outcome", outcome).register(registry).record(duration);
        registry.counter("scoring.training.runs", "outcome", outcome).increment();
    }

    void modelDecision(String decision) {
        registry.counter("scoring.models.decisions", "decision", decision).increment();
    }

    void psi(String scope, double value) {
        DistributionSummary.builder("scoring.monitoring.psi").tag("scope", scope).register(registry).record(value);
    }

    void driftAlert(String level, String kind) {
        registry.counter("scoring.monitoring.alerts", "level", level, "kind", kind).increment();
    }

    void outcomesResolved(int rows) {
        registry.counter("scoring.outcomes.resolved").increment(rows);
    }

    void cohortsMeasured(int cohorts) {
        registry.counter("scoring.cohorts.measured").increment(cohorts);
    }

    void factRecorded(String kind) {
        registry.counter("scoring.facts.recorded", "kind", kind).increment();
    }

    /** Evento consumido sem fato ({@code disabled}, {@code no_portfolio}, {@code duplicate}, {@code not_applicable}). */
    void factIgnored(String type, String reason) {
        registry.counter("scoring.facts.ignored", "type", type, "reason", reason).increment();
    }

    void eventConsumed(String type) {
        registry.counter("scoring.events.consumed", "type", type).increment();
    }

    void purged(String what, int rows) {
        registry.counter("scoring.retention.purged", "what", what).increment(rows);
    }

    void jobFailure(String job) {
        registry.counter("scoring.jobs.failures", "job", job).increment();
    }
}
