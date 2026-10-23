package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.debt.TitleStatusChanged;
import br.com.gestorcobranca.shared.events.negotiation.AgreementPaymentRecorded;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.events.negotiation.InstallmentStatusChanged;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Consumidores de eventos (idempotentes por {@code shared.consumed_event}): resultado da baixa no
 * negotiation (valor não aplicado vai para a fila de realocação) e encerramento de acordo, parcela ou
 * título (instruções abertas são canceladas).
 */
@Component
class PaymentReactions extends TenantEventListener {

    private final PaymentEventHandlers handlers;

    PaymentReactions(PaymentEventHandlers handlers) {
        this.handlers = handlers;
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(AgreementPaymentRecorded event) {
        handleOnce(event, "payment.agreement-payment-recorded", () -> handlers.agreementPaymentRecorded(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(AgreementStatusChanged event) {
        handleOnce(event, "payment.agreement-status-changed", () -> handlers.agreementStatusChanged(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(InstallmentStatusChanged event) {
        handleOnce(event, "payment.installment-status-changed", () -> handlers.installmentStatusChanged(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(TitleStatusChanged event) {
        handleOnce(event, "payment.title-status-changed", () -> handlers.titleStatusChanged(event));
    }
}
