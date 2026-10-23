package br.com.gestorcobranca.negotiation.application.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Métricas técnicas do módulo ({@code negotiation.*}, namespace CloudWatch {@code GestorCobranca}).
 * Somente contagens, durações e tags de baixa cardinalidade (tipo de ator, resultado, regra) —
 * nunca valores, ids de devedor ou de usuário.
 */
@Component
class NegotiationMetrics {

    private final MeterRegistry registry;

    NegotiationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** @param outcome OFFERED (ao menos uma oferta válida) ou NO_VALID_OFFER */
    void simulated(String actorType, String outcome, int offers, Duration duration) {
        Counter.builder("negotiation.simulations").tag("actorType", actorType).tag("outcome", outcome)
                .register(registry).increment();
        Counter.builder("negotiation.simulation.offers").tag("actorType", actorType).register(registry).increment(offers);
        Timer.builder("negotiation.simulation.duration").tag("actorType", actorType).register(registry).record(duration);
    }

    /** @param outcome WITHIN_AUTHORITY, REQUIRES_APPROVAL ou REJECTED */
    void guardDecision(String actorType, String outcome) {
        Counter.builder("negotiation.guard.decisions").tag("actorType", actorType).tag("outcome", outcome)
                .register(registry).increment();
    }

    void guardViolation(String code) {
        Counter.builder("negotiation.guard.violations").tag("rule", code).register(registry).increment();
    }

    /** Pedido de nível acima de 0 na escada (com justificativa). */
    void concessionEscalated(String actorType, int level, String reason) {
        Counter.builder("negotiation.concession.escalations").tag("actorType", actorType)
                .tag("level", String.valueOf(Math.min(level, 10))).tag("reason", reason).register(registry).increment();
    }

    void policyTransition(String status) {
        Counter.builder("negotiation.policy.transitions").tag("status", status).register(registry).increment();
    }

    /** @param operation PROFILE_CREATED, PROFILE_UPDATED, ASSIGNED, REASSIGNED, UNASSIGNED */
    void authorityChanged(String operation) {
        Counter.builder("negotiation.authority.changes").tag("operation", operation).register(registry).increment();
    }

    /** Cálculo usou competência de índice ainda não publicada (0%). Crescimento contínuo = tabela desatualizada. */
    void indexPending(String indexCode) {
        Counter.builder("negotiation.calculation.index_pending").tag("index", indexCode).register(registry).increment();
    }

    void profilesSeeded(int created) {
        Counter.builder("negotiation.authority.profiles_seeded").register(registry).increment(created);
    }

    void jobFailure(String job) {
        Counter.builder("negotiation.jobs.failures").tag("job", job).register(registry).increment();
    }
}
