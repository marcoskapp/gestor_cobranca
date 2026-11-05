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

    /** Ação automática da tabulação: {@code action} × {@code outcome} (DONE, ALREADY_IN_EFFECT, NO_TITLES). */
    void followUp(String action, String outcome) {
        registry.counter("collection.tabulation_follow_ups", "action", action, "outcome", outcome).increment();
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

    /** @param mode MANUAL, TAKE, AUTOMATIC, REDISTRIBUTION, RELEASE */
    void disputeAssignment(String mode) {
        registry.counter("collection.disputes.assignments", "mode", mode).increment();
    }

    /** @param purpose REQUEST, REMINDER, OVERDUE; @param outcome sent, rejected, transient, exhausted */
    void disputeMail(String purpose, String outcome) {
        registry.counter("collection.disputes.creditor_mails", "purpose", purpose, "outcome", outcome).increment();
    }

    void disputeMailDuration(Duration duration) {
        Timer.builder("collection.disputes.creditor_mail.duration").publishPercentiles(0.5, 0.95, 0.99)
                .register(registry).record(duration);
    }

    void disputeCreditorRequestOverdue(String type) {
        registry.counter("collection.disputes.creditor_overdue", "type", type).increment();
    }

    /** @param source MANUAL ou PUBLIC_API */
    void disputeCreditorResponse(String source) {
        registry.counter("collection.disputes.creditor_responses", "source", source).increment();
    }

    /** @param outcome queued, blocked, registered */
    void disputeDebtorNotice(String channel, String outcome) {
        registry.counter("collection.disputes.debtor_notices", "channel", channel, "outcome", outcome).increment();
    }

    /** @param status PENDING (sugerido), DONE, DISMISSED */
    void disputeAdjustment(String action, String status) {
        registry.counter("collection.disputes.adjustments", "action", action, "status", status).increment();
    }

    /** @param outcome served, expired, exhausted, unknown, malformed, rate_limited */
    void disputeLinkDownload(String outcome) {
        registry.counter("collection.disputes.evidence_link", "outcome", outcome).increment();
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

    /** @param outcome clean, not_scanned, infected, unavailable */
    void disputeDocumentScan(String engine, String outcome) {
        registry.counter("collection.disputes.document_scans", "engine", engine, "outcome", outcome).increment();
    }

    void disputeDocumentScanDuration(String engine, Duration duration) {
        Timer.builder("collection.disputes.document_scan.duration").tag("engine", engine)
                .publishPercentiles(0.5, 0.95, 0.99).register(registry).record(duration);
    }
}
