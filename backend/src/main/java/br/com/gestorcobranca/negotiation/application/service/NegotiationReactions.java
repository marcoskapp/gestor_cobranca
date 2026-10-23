package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.debt.DebtorAnonymized;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.events.payment.PaymentReversed;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Consumidores de eventos de acordos (idempotentes por {@code shared.consumed_event} e pelo registro
 * único por acordo e pagamento). Ficam em {@code application.service} para usar os serviços
 * internos do módulo sem expô-los como casos de uso.
 */
@Component
class NegotiationReactions extends TenantEventListener {

    private final AgreementPaymentService payments;
    private final NegotiationAnonymizer anonymizer;

    NegotiationReactions(AgreementPaymentService payments, NegotiationAnonymizer anonymizer) {
        this.payments = payments;
        this.anonymizer = anonymizer;
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PaymentConfirmed event) {
        handleOnce(event, "negotiation.payment-confirmed", () -> payments.paymentConfirmed(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PaymentReversed event) {
        handleOnce(event, "negotiation.payment-reversed", () -> payments.paymentReversed(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(DebtorAnonymized event) {
        handleOnce(event, "negotiation.debtor-anonymized", () -> anonymizer.anonymize(event.debtorId()));
    }
}
