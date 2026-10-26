package br.com.gestorcobranca.negotiation.application.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
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

    /** @param status DRAFT, PENDING_APPROVAL ou HANDED_OFF */
    void proposalCreated(String actorType, String status) {
        Counter.builder("negotiation.proposals").tag("actorType", actorType).tag("status", status)
                .register(registry).increment();
    }

    /** @param decision APPROVED ou REJECTED; {@code waitTime} = criação → decisão */
    void proposalDecided(String decision, Duration waitTime) {
        Counter.builder("negotiation.proposal.decisions").tag("decision", decision).register(registry).increment();
        Timer.builder("negotiation.proposal.approval_wait").tag("decision", decision).register(registry).record(waitTime);
    }

    void proposalAccepted(String channel) {
        Counter.builder("negotiation.proposal.acceptances").tag("channel", channel).register(registry).increment();
    }

    void proposalsExpired(int count) {
        Counter.builder("negotiation.proposal.expirations").register(registry).increment(count);
    }

    /** Transferência do assistente para humano (proposta acima da alçada do assistente). */
    void handoffRequested() {
        Counter.builder("negotiation.handoffs").register(registry).increment();
    }

    /** @param status novo status do acordo */
    void agreementTransition(String status, String origin) {
        Counter.builder("negotiation.agreement.transitions").tag("status", status).tag("origin", origin)
                .register(registry).increment();
    }

    void formalization(Duration duration) {
        Timer.builder("negotiation.formalization.duration").register(registry).record(duration);
    }

    void termRendered(Duration duration) {
        Timer.builder("negotiation.term.render.duration").register(registry).record(duration);
    }

    /**
     * Avaliação de acordo que atingiu a regra de quebra: BREAK, BREAK_SUSPENSION_USED, SUSPEND,
     * HOLD_SUSPENDED, HOLD_GRACE ou DEFER_IN_TRANSIT ({@code BreakHoldRules.Outcome}).
     */
    void breakEvaluated(String outcome) {
        Counter.builder("negotiation.agreement.break_evaluations").tag("outcome", outcome).register(registry).increment();
    }

    void breakSuspensionStarted() {
        Counter.builder("negotiation.agreement.break_suspensions").tag("event", "STARTED").tag("reason", "NONE")
                .register(registry).increment();
    }

    /** @param reason APPROVED, REJECTED, DEADLINE ou AGREEMENT_ENDED; {@code days} = dias corridos suspensa */
    void breakSuspensionEnded(String reason, long days) {
        Counter.builder("negotiation.agreement.break_suspensions").tag("event", "ENDED").tag("reason", reason)
                .register(registry).increment();
        DistributionSummary.builder("negotiation.agreement.break_suspension.days").baseUnit("days").tag("reason", reason)
                .register(registry).record(days);
    }

    void installmentOverdue() {
        Counter.builder("negotiation.installments.overdue").register(registry).increment();
    }

    /** @param outcome APPLIED, UNAPPLIED, PARTIAL (com excedente), DUPLICATE, IGNORED (acordo desconhecido) */
    void paymentConsumed(String outcome) {
        Counter.builder("negotiation.payments").tag("outcome", outcome).register(registry).increment();
    }

    /** @param outcome REVERSED, UNKNOWN (pagamento não aplicado a acordo), DUPLICATE */
    void reversalConsumed(String outcome) {
        Counter.builder("negotiation.payment.reversals").tag("outcome", outcome).register(registry).increment();
    }

    /** @param operation PAYMENT (inclui a quitação do título) ou REVERSAL; @param outcome POSTED ou FAILED */
    void ledgerPosting(String operation, String outcome) {
        Counter.builder("negotiation.ledger.postings").tag("operation", operation).tag("outcome", outcome)
                .register(registry).increment();
    }

    void anonymized(int records) {
        Counter.builder("negotiation.anonymized_records").register(registry).increment(records);
    }

    void jobFailure(String job) {
        Counter.builder("negotiation.jobs.failures").tag("job", job).register(registry).increment();
    }
}
