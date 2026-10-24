package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.collection.InteractionRecorded;
import br.com.gestorcobranca.shared.events.collection.PromiseStatusChanged;
import br.com.gestorcobranca.shared.events.communication.MessageStatusChanged;
import br.com.gestorcobranca.shared.events.debt.DebtorAnonymized;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.events.payment.PaymentReversed;
import br.com.gestorcobranca.shared.events.telephony.CallCompleted;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Consumidores dos eventos de integração (idempotentes por evento em {@code shared.consumed_event}
 * e pela chave do fato em {@code scoring.case_fact}).
 */
@Component
class ScoringEventListeners extends TenantEventListener {

    private final FactRecorder facts;
    private final ScoringMetrics metrics;

    ScoringEventListeners(FactRecorder facts, ScoringMetrics metrics) {
        this.facts = facts;
        this.metrics = metrics;
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(InteractionRecorded event) {
        handleOnce(event, "scoring.interaction", () -> {
            metrics.eventConsumed("interaction");
            facts.interaction(event);
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(MessageStatusChanged event) {
        handleOnce(event, "scoring.message", () -> {
            metrics.eventConsumed("message");
            facts.message(event);
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(CallCompleted event) {
        handleOnce(event, "scoring.call", () -> {
            metrics.eventConsumed("call");
            facts.call(event);
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PromiseStatusChanged event) {
        handleOnce(event, "scoring.promise", () -> {
            metrics.eventConsumed("promise");
            facts.promise(event);
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(AgreementStatusChanged event) {
        handleOnce(event, "scoring.agreement", () -> {
            metrics.eventConsumed("agreement");
            facts.agreement(event);
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PaymentConfirmed event) {
        handleOnce(event, "scoring.payment-confirmed", () -> {
            metrics.eventConsumed("payment_confirmed");
            facts.payment(event);
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PaymentReversed event) {
        handleOnce(event, "scoring.payment-reversed", () -> {
            metrics.eventConsumed("payment_reversed");
            facts.reversal(event);
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(DebtorAnonymized event) {
        handleOnce(event, "scoring.debtor-anonymized", () -> {
            metrics.eventConsumed("debtor_anonymized");
            facts.debtorAnonymized(event);
        });
    }
}
