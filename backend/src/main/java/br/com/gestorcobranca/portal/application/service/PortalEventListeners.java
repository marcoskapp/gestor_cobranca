package br.com.gestorcobranca.portal.application.service;

import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.debt.DebtorAnonymized;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Consumidores de eventos de integração do portal (idempotentes por {@code shared.consumed_event}). */
@Component
class PortalEventListeners extends TenantEventListener {

    private final PortalMaintenance maintenance;

    PortalEventListeners(PortalMaintenance maintenance) {
        this.maintenance = maintenance;
    }

    /** Primeiro pagamento de acordo fechado no portal: etapa final do funil. */
    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PaymentConfirmed event) {
        handleOnce(event, "portal.payment-confirmed", () -> maintenance.paymentConfirmed(event));
    }

    /** LGPD: revoga sessões e links e apaga dados do devedor guardados pelo portal. */
    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(DebtorAnonymized event) {
        handleOnce(event, "portal.debtor-anonymized", () -> maintenance.debtorAnonymized(event));
    }
}
