package br.com.gestorcobranca.collection.application.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Métricas do módulo (Micrometer → CloudWatch). Os nomes e tags são contrato com painéis e
 * alarmes, documentados em {@code docs/tecnico/logs-e-metricas.md} §5 — não renomear. Tags só com valores de
 * baixa cardinalidade (enums, faixas); nunca ids.
 */
@Component
class CollectionMetrics {

    private final MeterRegistry registry;

    CollectionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void caseOpened(String trigger) {
        registry.counter("collection.cases.opened", "trigger", trigger).increment();
    }

    void caseClosed(String reason) {
        registry.counter("collection.cases.closed", "reason", reason).increment();
    }

    void caseTransition(String from, String to) {
        registry.counter("collection.cases.transitions", "from", from, "to", to).increment();
    }

    void eligibilityEvaluated(boolean eligible, boolean changed) {
        registry.counter("collection.eligibility.evaluations", "outcome", eligible ? "eligible" : "ineligible",
                "changed", Boolean.toString(changed)).increment();
    }

    void ineligibleReason(String reason) {
        registry.counter("collection.eligibility.reasons", "reason", reason).increment();
    }

    void assignment(String mode, String ownerType) {
        registry.counter("collection.assignments", "mode", mode, "owner", ownerType).increment();
    }

    /** @param outcome own_callback, queue, empty, capacity */
    void nextCase(String outcome, Duration duration) {
        registry.counter("collection.next_case", "outcome", outcome).increment();
        Timer.builder("collection.next_case.duration").tag("outcome", outcome).publishPercentiles(0.5, 0.95, 0.99)
                .register(registry).record(duration);
    }

    void distribution(String strategy, int assigned, int unassigned) {
        registry.counter("collection.distribution.cases", "strategy", strategy, "outcome", "assigned").increment(assigned);
        registry.counter("collection.distribution.cases", "strategy", strategy, "outcome", "unassigned")
                .increment(unassigned);
    }

    void interaction(String channel, String direction, boolean effective, String source) {
        registry.counter("collection.interactions", "channel", channel, "direction", direction,
                "effective", Boolean.toString(effective), "source", source).increment();
    }

    void callback(String status) {
        registry.counter("collection.callbacks", "status", status).increment();
    }

    void promise(String status) {
        registry.counter("collection.promises", "status", status).increment();
    }

    void dispute(String status) {
        registry.counter("collection.disputes", "status", status).increment();
    }

    void disputeOverdue(String type) {
        registry.counter("collection.disputes.overdue", "type", type).increment();
    }

    void sync(String trigger, int cases, Duration duration) {
        registry.counter("collection.sync.cases", "trigger", trigger).increment(cases);
        Timer.builder("collection.sync.duration").tag("trigger", trigger).register(registry).record(duration);
    }

    void eventConsumed(String type) {
        registry.counter("collection.events.consumed", "type", type).increment();
    }

    void jobFailure(String job) {
        registry.counter("collection.jobs.failures", "job", job).increment();
    }
}
