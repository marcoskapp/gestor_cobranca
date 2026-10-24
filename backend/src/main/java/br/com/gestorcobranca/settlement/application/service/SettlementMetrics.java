package br.com.gestorcobranca.settlement.application.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Métricas técnicas do módulo ({@code settlement.*}, namespace CloudWatch {@code GestorCobranca}). */
@Component
class SettlementMetrics {

    private final MeterRegistry registry;

    SettlementMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** @param outcome recorded, duplicate, ignored */
    void receipt(String kind, String outcome) {
        Counter.builder("settlement.receipts").tag("kind", kind).tag("outcome", outcome).register(registry).increment();
    }

    /** @param outcome rated, pending_rules */
    void rating(String kind, String outcome) {
        Counter.builder("settlement.ratings").tag("kind", kind).tag("outcome", outcome).register(registry).increment();
    }

    void eventIgnored(String type, String reason) {
        Counter.builder("settlement.events.ignored").tag("type", type).tag("reason", reason).register(registry)
                .increment();
    }

    void expense(String kind, String effect) {
        Counter.builder("settlement.expenses").tag("kind", kind).tag("effect", effect).register(registry).increment();
    }

    void statementTransition(String status) {
        Counter.builder("settlement.statements.transitions").tag("status", status).register(registry).increment();
    }

    void calculated(Duration duration, int receipts) {
        Timer.builder("settlement.statement.calculate.duration").register(registry).record(duration);
        Counter.builder("settlement.statement.receipts").register(registry).increment(receipts);
    }

    void adjustment(String status) {
        Counter.builder("settlement.adjustments").tag("status", status).register(registry).increment();
    }

    void exported(String format) {
        Counter.builder("settlement.exports").tag("format", format).register(registry).increment();
    }

    void transfer(String direction, String outcome) {
        Counter.builder("settlement.transfers").tag("direction", direction).tag("outcome", outcome).register(registry)
                .increment();
    }

    void rulesPublished() {
        Counter.builder("settlement.rules.published").register(registry).increment();
    }
}
