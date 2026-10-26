package br.com.gestorcobranca.helpdesk.application.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Métricas do chat de suporte (docs/tecnico/logs-e-metricas.md §5), separadas em técnicas ({@code helpdesk.turns},
 * latência, chamadas ao modelo, tokens, custo, ferramentas, limites, chamados) e de negócio agregadas
 * ({@code helpdesk.questions{topic,outcome}}, {@code helpdesk.tickets.opened{screen}}, {@code helpdesk.feedback}).
 * Tags só com códigos de catálogo (tema, resultado, ferramenta, chave de tela do manual): nada vindo do texto.
 */
@Component
class HelpdeskMetrics {

    private final MeterRegistry meters;

    HelpdeskMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    void turn(String provider, String outcome, Duration duration) {
        meters.counter("helpdesk.turns", "provider", provider, "outcome", outcome).increment();
        Timer.builder("helpdesk.turn.duration").tag("outcome", outcome).publishPercentiles(0.5, 0.95, 0.99)
                .register(meters).record(duration);
    }

    void modelCall(String provider, String outcome, Duration latency) {
        meters.counter("helpdesk.model.calls", "provider", provider, "outcome", outcome).increment();
        Timer.builder("helpdesk.model.latency").tag("provider", provider).publishPercentiles(0.5, 0.95, 0.99)
                .register(meters).record(latency);
    }

    void tokens(String provider, String direction, long amount) {
        if (amount > 0) {
            meters.counter("helpdesk.tokens", "provider", provider, "direction", direction).increment(amount);
        }
    }

    void cost(String provider, String currency, BigDecimal amount) {
        if (amount != null && amount.signum() > 0) {
            meters.counter("helpdesk.cost", "provider", provider, "currency", currency == null ? "none" : currency)
                    .increment(amount.doubleValue());
        }
    }

    void tool(String tool, String outcome) {
        meters.counter("helpdesk.tool.calls", "tool", tool, "outcome", outcome).increment();
    }

    void limit(String code) {
        meters.counter("helpdesk.limits.reached", "limit", code).increment();
    }

    void unavailable() {
        meters.counter("helpdesk.unavailable").increment();
    }

    void question(String topic, String outcome, String screen) {
        meters.counter("helpdesk.questions", "topic", topic, "outcome", outcome).increment();
        if (!"ANSWERED".equals(outcome)) {
            meters.counter("helpdesk.questions.unanswered", "screen", screen == null ? "none" : screen).increment();
        }
    }

    void feedback(String rating, String screen) {
        meters.counter("helpdesk.feedback", "rating", rating, "screen", screen == null ? "none" : screen).increment();
    }

    void ticket(String outcome, String screen) {
        meters.counter("helpdesk.tickets", "outcome", outcome).increment();
        if ("OPENED".equals(outcome)) {
            meters.counter("helpdesk.tickets.opened", "screen", screen == null ? "none" : screen).increment();
        }
    }

    void purged(long count) {
        if (count > 0) {
            meters.counter("helpdesk.retention.purged").increment(count);
        }
    }

    void jobFailure(String job) {
        meters.counter("helpdesk.jobs.failures", "job", job).increment();
    }
}
