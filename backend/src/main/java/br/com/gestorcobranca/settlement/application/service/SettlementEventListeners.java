package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.legal.LegalExpenseChanged;
import br.com.gestorcobranca.shared.events.negotiation.AgreementAttributionRecorded;
import br.com.gestorcobranca.shared.events.negotiation.AgreementPaymentRecorded;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.events.payment.PaymentReversed;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Consumidores dos eventos de integração do módulo. Idempotentes por evento ({@code shared.consumed_event})
 * e pela chave natural (alocação, estorno, acordo, despesa) sob lock — eventos da mesma alocação podem chegar
 * em qualquer ordem.
 */
@Component
class SettlementEventListeners extends TenantEventListener {

    private final ReceiptPostingService posting;
    private final FactIngestionService facts;

    SettlementEventListeners(ReceiptPostingService posting, FactIngestionService facts) {
        this.posting = posting;
        this.facts = facts;
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PaymentConfirmed event) {
        handleOnce(event, "settlement.payment-confirmed", () -> posting.paymentConfirmed(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(AgreementPaymentRecorded event) {
        handleOnce(event, "settlement.agreement-payment-recorded", () -> posting.agreementPaymentRecorded(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PaymentReversed event) {
        handleOnce(event, "settlement.payment-reversed", () -> posting.paymentReversed(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(AgreementAttributionRecorded event) {
        handleOnce(event, "settlement.agreement-attribution", () -> facts.agreementAttributed(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(AgreementStatusChanged event) {
        handleOnce(event, "settlement.agreement-status", () -> facts.agreementStatusChanged(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(LegalExpenseChanged event) {
        handleOnce(event, "settlement.legal-expense", () -> facts.legalExpenseChanged(event));
    }
}
