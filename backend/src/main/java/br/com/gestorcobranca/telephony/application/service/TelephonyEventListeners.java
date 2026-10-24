package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.debt.DebtorAnonymized;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Consumidores de eventos de integração, idempotentes por evento ({@code shared.consumed_event}).
 * Nomes de consumidor estáveis: renomear faz eventos já consumidos serem reprocessados.
 */
@Component
class TelephonyEventListeners extends TenantEventListener {

    private final TelephonyAnonymizer anonymizer;

    TelephonyEventListeners(TelephonyAnonymizer anonymizer) {
        this.anonymizer = anonymizer;
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(DebtorAnonymized event) {
        handleOnce(event, "telephony.debtor-anonymized", () -> anonymizer.anonymize(event.debtorId()));
    }
}
