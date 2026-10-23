package br.com.gestorcobranca.communication.application.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Métricas do módulo (Micrometer → CloudWatch). Nomes e tags são contrato com painéis e alarmes,
 * documentados em {@code docs/LOGGING.md} §5 — não renomear. Tags só com valores de baixa
 * cardinalidade (enums, códigos estáveis); nunca ids ou contatos.
 */
@Component
class CommunicationMetrics {

    private final MeterRegistry registry;

    CommunicationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Mudança de status de mensagem (inclui RECEIVED e QUEUED). */
    void messageStatus(String channel, String direction, String sender, String status) {
        registry.counter("communication.messages", "channel", channel, "direction", direction, "sender", sender,
                "status", status).increment();
    }

    /** Envio negado pelo porteiro de contato; um incremento por motivo. */
    void blocked(String channel, String sender, String stage, String reason) {
        registry.counter("communication.messages.blocked", "channel", channel, "sender", sender, "stage", stage,
                "reason", reason).increment();
    }

    /** @param outcome accepted, rejected, transient, blocked, exhausted, account_unavailable */
    void sendAttempt(String channel, String provider, String outcome) {
        registry.counter("communication.send.attempts", "channel", channel, "provider", provider, "outcome", outcome)
                .increment();
    }

    /** Do enfileiramento ao aceite do provedor. */
    void sendLatency(String channel, Duration duration) {
        Timer.builder("communication.send.latency").tag("channel", channel).publishPercentiles(0.5, 0.95, 0.99)
                .register(registry).record(duration);
    }

    /** Do aceite à confirmação de entrega. */
    void deliveryLatency(String channel, Duration duration) {
        Timer.builder("communication.delivery.latency").tag("channel", channel).publishPercentiles(0.5, 0.95, 0.99)
                .register(registry).record(duration);
    }

    /** @param outcome ACCEPTED, DUPLICATE, UNKNOWN_ROUTE, INVALID_SIGNATURE, MALFORMED, TOO_LARGE, RATE_LIMITED, VERIFIED, VERIFY_REJECTED */
    void webhookRequest(String provider, String outcome) {
        registry.counter("communication.webhook.requests", "provider", provider, "outcome", outcome).increment();
    }

    /** @param outcome applied, duplicate, stale, unknown_message, ignored */
    void webhookEvent(String provider, String type, String outcome) {
        registry.counter("communication.webhook.events", "provider", provider, "type", type, "outcome", outcome)
                .increment();
    }

    void optOut(String channel) {
        registry.counter("communication.opt_out", "channel", channel).increment();
    }

    void handoff(String kind, String from, String to) {
        registry.counter("communication.handoffs", "kind", kind, "from", from, "to", to).increment();
    }

    /** Tempo até a primeira resposta de operador ou assistente (SLA da caixa de entrada). */
    void firstResponse(String channel, String responder, Duration waited) {
        Timer.builder("communication.inbox.first_response").tag("channel", channel).tag("responder", responder)
                .publishPercentiles(0.5, 0.95, 0.99).register(registry).record(waited);
    }

    /** @param outcome recorded, duplicate, skipped_no_case, skipped_case_closed, skipped_tabulation */
    void interaction(String direction, String outcome) {
        registry.counter("communication.interactions", "direction", direction, "outcome", outcome).increment();
    }

    /** @param outcome proposed, sent, edited, discarded */
    void draft(String mode, String outcome) {
        registry.counter("communication.drafts", "mode", mode, "outcome", outcome).increment();
    }

    void contentPurged(int count) {
        registry.counter("communication.retention.purged").increment(count);
    }

    void jobFailure(String job) {
        registry.counter("communication.jobs.failures", "job", job).increment();
    }

    void eventConsumed(String type) {
        registry.counter("communication.events.consumed", "type", type).increment();
    }
}
