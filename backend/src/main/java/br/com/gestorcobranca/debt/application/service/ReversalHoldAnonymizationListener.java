package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.debt.DebtorAnonymized;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Anonimização ou eliminação do titular (LGPD, RS-122): as retenções pendentes após estorno terminam ({@code ENDED})
 * sem retomar contato e a justificativa das liberações (texto livre) é limpa. Consumidor do evento, sem tocar a
 * anonimização do devedor ({@code DebtorService.anonymize}).
 */
@Component
class ReversalHoldAnonymizationListener extends TenantEventListener {

    private final ReversalHoldService holds;

    ReversalHoldAnonymizationListener(ReversalHoldService holds) {
        this.holds = holds;
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(DebtorAnonymized event) {
        handleOnce(event, "debt.reversal-hold-anonymized", () -> holds.endForAnonymizedDebtor(event.debtorId()));
    }
}
