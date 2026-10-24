package br.com.gestorcobranca.assistant.application.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Métricas do módulo (Micrometer → CloudWatch). Nomes e tags são contrato com painéis e alarmes,
 * documentados em {@code docs/tecnico/logs-e-metricas.md} §5 — não renomear. Tags só com valores de
 * baixa cardinalidade (modos, resultados, motivos, códigos); nunca ids.
 */
@Component
class AssistantMetrics {

    private final MeterRegistry registry;

    AssistantMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void turn(String mode, String outcome, Duration duration) {
        registry.counter("assistant.turns", "mode", mode, "outcome", outcome).increment();
        Timer.builder("assistant.turn.duration").tag("mode", mode).tag("outcome", outcome)
                .publishPercentiles(0.5, 0.95, 0.99).register(registry).record(duration);
    }

    void modelCall(String provider, String outcome, Duration latency) {
        registry.counter("assistant.model.calls", "provider", provider, "outcome", outcome).increment();
        if (latency != null) {
            Timer.builder("assistant.model.latency").tag("provider", provider)
                    .publishPercentiles(0.5, 0.95, 0.99).register(registry).record(latency);
        }
    }

    void tokens(String provider, long input, long output) {
        if (input > 0) {
            registry.counter("assistant.tokens", "provider", provider, "direction", "input").increment(input);
        }
        if (output > 0) {
            registry.counter("assistant.tokens", "provider", provider, "direction", "output").increment(output);
        }
    }

    void cost(String provider, String currency, BigDecimal amount) {
        if (amount != null && amount.signum() > 0) {
            registry.counter("assistant.cost", "provider", provider, "currency", currency == null ? "NA" : currency)
                    .increment(amount.doubleValue());
        }
    }

    void tool(String tool, String outcome) {
        registry.counter("assistant.tool.calls", "tool", tool, "outcome", outcome).increment();
    }

    void handoff(String reason, String source) {
        registry.counter("assistant.handoffs", "reason", reason, "source", source).increment();
    }

    void guardrail(String code) {
        registry.counter("assistant.guardrails", "code", code).increment();
    }

    void draft(String mode) {
        registry.counter("assistant.drafts", "mode", mode).increment();
    }

    void identity(String outcome) {
        registry.counter("assistant.identity.checks", "outcome", outcome).increment();
    }

    void policy(String status) {
        registry.counter("assistant.policy.transitions", "status", status).increment();
    }

    void review(String verdict) {
        registry.counter("assistant.reviews", "verdict", verdict).increment();
    }

    void scheduled(String outcome) {
        registry.counter("assistant.turns.scheduled", "outcome", outcome).increment();
    }

    void recovered() {
        registry.counter("assistant.turns.recovered").increment();
    }

    void purged(int count) {
        if (count > 0) {
            registry.counter("assistant.retention.purged").increment(count);
        }
    }

    void eventConsumed(String type) {
        registry.counter("assistant.events.consumed", "type", type).increment();
    }

    // ------------------------------------------------------------------ Pix na conversa

    void pixIssued(String mode, boolean reused) {
        registry.counter("assistant.pix.issued", "mode", mode, "reused", String.valueOf(reused)).increment();
    }

    void pixDenied(String reason) {
        registry.counter("assistant.pix.denied", "reason", reason).increment();
    }

    void pixSuggested(String mode) {
        registry.counter("assistant.pix.suggested", "mode", mode).increment();
    }

    /** @param outcome DELIVERED, NOT_DELIVERED ou CANCELLED; {@code code} = motivo da não entrega */
    void pixDelivery(String outcome, String code, Duration sinceRequest) {
        registry.counter("assistant.pix.deliveries", "outcome", outcome, "code", code == null ? "NA" : code).increment();
        if (sinceRequest != null && "DELIVERED".equals(outcome)) {
            Timer.builder("assistant.pix.delivery.latency").publishPercentiles(0.5, 0.95, 0.99).register(registry)
                    .record(sinceRequest);
        }
    }

    void pixPaid(boolean within24h) {
        registry.counter("assistant.pix.paid", "within_24h", String.valueOf(within24h)).increment();
    }

    void jobFailure(String job) {
        registry.counter("assistant.jobs.failures", "job", job).increment();
    }
}
