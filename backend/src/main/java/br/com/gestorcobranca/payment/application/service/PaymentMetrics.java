package br.com.gestorcobranca.payment.application.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Métricas do módulo (Micrometer → CloudWatch). Nomes e tags são contrato com painéis e alarmes,
 * documentados em {@code docs/tecnico/logs-e-metricas.md} §5 — não renomear. Tags só com valores de
 * baixa cardinalidade (enums, códigos); nunca ids, valores ou documentos.
 */
@Component
class PaymentMetrics {

    private final MeterRegistry registry;

    PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** @param outcome created, reused, registered, failed, expired, cancelled, paid, provider_unavailable */
    void instruction(String type, String outcome) {
        registry.counter("payment.instructions", "type", type, "outcome", outcome).increment();
    }

    /** Transição de pagamento (INFORMED, CONFIRMED, RECONCILED, REJECTED, REVERSED). */
    void payment(String source, String method, String status) {
        registry.counter("payment.payments", "source", source, "method", method, "status", status).increment();
    }

    /** Do pagamento (horário informado pelo PSP/operador) à confirmação no sistema. */
    void confirmationLatency(String source, Duration duration) {
        Timer.builder("payment.confirmation.latency").tag("source", source).publishPercentiles(0.5, 0.95, 0.99)
                .register(registry).record(duration.isNegative() ? Duration.ZERO : duration);
    }

    /** @param outcome allocated, leftover (sobra), reversed, released */
    void allocation(String kind, String origin, String outcome) {
        registry.counter("payment.allocations", "kind", kind, "origin", origin, "outcome", outcome).increment();
    }

    /** Resultado da baixa no negotiation: APPLIED, PARTIAL, UNAPPLIED. */
    void agreementOutcome(String outcome) {
        registry.counter("payment.agreement.outcomes", "outcome", outcome).increment();
    }

    /** @param reason no_target, agreement_closed, title_unavailable, excess, duplicate */
    void leftover(String reason) {
        registry.counter("payment.leftovers", "reason", reason).increment();
    }

    void review(String decision, Duration wait) {
        registry.counter("payment.review.decisions", "decision", decision).increment();
        Timer.builder("payment.review.wait").tag("decision", decision).publishPercentiles(0.5, 0.95)
                .register(registry).record(wait.isNegative() ? Duration.ZERO : wait);
    }

    void reversal(String reason, String source) {
        registry.counter("payment.reversals", "reason", reason, "source", source).increment();
    }

    /** @param outcome ACCEPTED, DUPLICATE, UNKNOWN_ROUTE, UNAUTHENTICATED (alarme), MALFORMED, TOO_LARGE, RATE_LIMITED */
    void webhookRequest(String provider, String outcome) {
        registry.counter("payment.webhook.requests", "provider", provider, "outcome", outcome).increment();
    }

    /** @param outcome scheduled, stale, invalid */
    void webhookEvent(String outcome) {
        registry.counter("payment.webhook.events", "outcome", outcome).increment();
    }

    /** Confirmação por consulta ao PSP: confirmed, linked, duplicate_payment, unidentified, refund, unchanged. */
    void pixSync(String outcome) {
        registry.counter("payment.pix.sync", "outcome", outcome).increment();
    }

    void statementLine(String outcome) {
        registry.counter("payment.reconciliation.lines", "outcome", outcome).increment();
    }

    void statementImport(String format, String status) {
        registry.counter("payment.reconciliation.imports", "format", format, "status", status).increment();
    }

    void divergence(String type, String status) {
        registry.counter("payment.divergences", "type", type, "status", status).increment();
    }

    void ledgerPosting(String operation, String outcome) {
        registry.counter("payment.ledger.postings", "operation", operation, "outcome", outcome).increment();
    }

    void jobFailure(String job) {
        registry.counter("payment.jobs.failures", "job", job).increment();
    }

    void eventConsumed(String type) {
        registry.counter("payment.events.consumed", "type", type).increment();
    }
}
