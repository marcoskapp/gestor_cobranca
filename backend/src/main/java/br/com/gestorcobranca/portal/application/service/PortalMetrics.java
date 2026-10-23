package br.com.gestorcobranca.portal.application.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Métricas {@code portal.*} (catálogo em docs/tecnico/logs-e-metricas.md §5). Rótulos de baixa
 * cardinalidade: nunca credor, devedor ou slug.
 */
@Component
class PortalMetrics {

    /** Etapas do funil, contadas uma vez por sessão (pagamento: uma vez por acordo). */
    enum Step { ACCESS, SIMULATION, ACCEPTANCE, PIX_ISSUED, PAYMENT_CONFIRMED }

    private final MeterRegistry registry;

    PortalMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Pedido de código: {@code outcome} = SENT, DECOY, NOT_DELIVERED. */
    void codeRequested(String channel, String outcome) {
        registry.counter("portal.access.code_requests", "channel", channel, "outcome", outcome).increment();
    }

    /** Resultado da verificação: {@code method} OTP/LINK; {@code outcome} SUCCESS, INVALID, KNOWLEDGE_REQUIRED. */
    void login(String method, String outcome) {
        registry.counter("portal.access.logins", "method", method, "outcome", outcome).increment();
    }

    /** Bloqueio progressivo aplicado ({@code key} DOCUMENT/IP) ou requisição recusada por bloqueio vigente. */
    void locked(String key, String event) {
        registry.counter("portal.access.locks", "key", key, "event", event).increment();
    }

    void linkIssued(String channel, String outcome) {
        registry.counter("portal.access.links", "channel", channel, "outcome", outcome).increment();
    }

    /** {@code event}: OPENED, REFRESHED, LOGOUT, REVOKED, EXPIRED. */
    void session(String event) {
        registry.counter("portal.sessions", "event", event).increment();
    }

    void funnel(Step step) {
        registry.counter("portal.funnel", "step", step.name()).increment();
    }

    /** Oferta pedida no aceite que não é válida/dentro da alçada do canal (recusada no servidor). */
    void offerRefused(String reason) {
        registry.counter("portal.offers.refused", "reason", reason).increment();
    }

    /** {@code plan}: CASH (à vista) ou INSTALLMENTS. */
    void agreementFormalized(String plan) {
        registry.counter("portal.agreements.formalized", "plan", plan).increment();
    }

    /** {@code outcome}: ISSUED, REUSED, FAILED. */
    void pix(String outcome) {
        registry.counter("portal.pix", "outcome", outcome).increment();
    }

    /** {@code outcome}: CREATED, FORWARDED, UNDELIVERED. */
    void serviceRequest(String reason, String outcome) {
        registry.counter("portal.service_requests", "reason", reason, "outcome", outcome).increment();
    }

    void jobFailure(String job) {
        Counter.builder("portal.job.failures").tag("job", job).register(registry).increment();
    }
}
