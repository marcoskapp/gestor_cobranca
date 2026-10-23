package br.com.gestorcobranca.legal.application.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Métricas Micrometer do módulo legal (documentadas em {@code docs/LOGGING.md} §5). Tags de
 * cardinalidade fixa (enums, códigos de provedor); nunca ids.
 */
@Component
class LegalMetrics {

    private final MeterRegistry registry;

    LegalMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void referred(Object origin) {
        registry.counter("legal.matters.referred", "origin", String.valueOf(origin)).increment();
    }

    void transition(Object from, Object to) {
        registry.counter("legal.matters.transitions", "from", String.valueOf(from), "to", String.valueOf(to)).increment();
    }

    void eligibility(String outcome) {
        registry.counter("legal.eligibility.evaluations", "outcome", outcome).increment();
    }

    void eligibilityReason(String reason) {
        registry.counter("legal.eligibility.reasons", "reason", reason).increment();
    }

    void viability(boolean viable, boolean economicallyViable) {
        registry.counter("legal.viability.assessments", "viable", String.valueOf(viable), "economicallyViable",
                String.valueOf(economicallyViable)).increment();
    }

    void authorization(Object decision) {
        registry.counter("legal.authorizations", "decision", String.valueOf(decision)).increment();
    }

    void measureTransition(Object type, Object to) {
        registry.counter("legal.measures.transitions", "type", String.valueOf(type), "to", String.valueOf(to)).increment();
    }

    void lawsuitFiled(Object route) {
        registry.counter("legal.lawsuits.filed", "route", String.valueOf(route)).increment();
    }

    void movement(Object type, Object source) {
        registry.counter("legal.movements", "type", String.valueOf(type), "source", String.valueOf(source)).increment();
    }

    void deadlineCreated(Object kind, Object mode) {
        registry.counter("legal.deadlines.created", "kind", String.valueOf(kind), "mode", String.valueOf(mode)).increment();
    }

    void deadlineAlert(int level) {
        registry.counter("legal.deadlines.alerts", "level", String.valueOf(level)).increment();
    }

    void deadlineMissed(Object kind) {
        registry.counter("legal.deadlines.missed", "kind", String.valueOf(kind)).increment();
    }

    void deadlineMet(boolean onTime) {
        registry.counter("legal.deadlines.met", "onTime", String.valueOf(onTime)).increment();
    }

    void deadlinesRescheduled(String trigger, int count) {
        if (count > 0) {
            registry.counter("legal.deadlines.rescheduled", "trigger", trigger).increment(count);
        }
    }

    void hearing(Object status) {
        registry.counter("legal.hearings", "status", String.valueOf(status)).increment();
    }

    void expense(Object type, Object advancedBy, boolean overBudget) {
        registry.counter("legal.expenses.recorded", "type", String.valueOf(type), "advancedBy", String.valueOf(advancedBy),
                "overBudget", String.valueOf(overBudget)).increment();
    }

    void expenseBlocked(String reason) {
        registry.counter("legal.expenses.blocked", "reason", reason).increment();
    }

    void collectionHold(String operation, String outcome) {
        registry.counter("legal.collection.holds", "operation", operation, "outcome", outcome).increment();
    }

    void prescriptionInterruption(Object type, boolean registered) {
        registry.counter("legal.prescription.interruptions", "type", String.valueOf(type), "outcome",
                registered ? "registered" : "duplicate").increment();
    }

    void manualProvider(String port, String operation) {
        registry.counter("legal.providers.manual_requests", "port", port, "operation", operation).increment();
    }

    void captured(String provider, int count) {
        if (count > 0) {
            registry.counter("legal.providers.captured", "provider", provider).increment(count);
        }
    }

    void scopeDenied(String resource) {
        registry.counter("legal.access.law_firm_denied", "resource", resource).increment();
    }

    void eventConsumed(String type) {
        registry.counter("legal.events.consumed", "type", type).increment();
    }

    void jobFailure(String job) {
        registry.counter("legal.jobs.failures", "job", job).increment();
    }

    Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    void stopScan(Timer.Sample sample, String phase) {
        sample.stop(registry.timer("legal.scan.duration", "phase", phase));
    }
}
