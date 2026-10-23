package br.com.gestorcobranca.incentive.application.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Métricas técnicas do módulo ({@code incentive.*}, namespace CloudWatch {@code GestorCobranca}).
 * Somente contagens e durações — nunca valores de remuneração.
 */
@Component
class IncentiveMetrics {

    private final MeterRegistry registry;

    IncentiveMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Fato consumido de evento de integração. */
    void factConsumed(String type) {
        Counter.builder("incentive.facts.consumed").tag("type", type).register(registry).increment();
    }

    void ledgerEntry(String kind, String beneficiaryType) {
        Counter.builder("incentive.ledger.entries").tag("kind", kind).tag("beneficiary", beneficiaryType)
                .register(registry).increment();
    }

    void ledgerTransition(String status) {
        Counter.builder("incentive.ledger.transitions").tag("status", status).register(registry).increment();
    }

    /** Pagamento recebido antes da atribuição do acordo (lançado quando ela chegar). */
    void paymentAwaitingAttribution() {
        Counter.builder("incentive.payments.awaiting_attribution").register(registry).increment();
    }

    void periodClosed(String trigger, int statements, Duration duration) {
        Timer.builder("incentive.period.close.duration").tag("trigger", trigger).register(registry).record(duration);
        Counter.builder("incentive.statements.calculated").tag("trigger", trigger).register(registry).increment(statements);
    }

    void statementTransition(String status) {
        Counter.builder("incentive.statements.transitions").tag("status", status).register(registry).increment();
    }

    void exported(String format) {
        Counter.builder("incentive.exports").tag("format", format).register(registry).increment();
    }

    void jobFailure(String job) {
        Counter.builder("incentive.jobs.failures").tag("job", job).register(registry).increment();
    }
}
