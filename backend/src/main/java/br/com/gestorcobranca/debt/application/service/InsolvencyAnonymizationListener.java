package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.debt.DebtorAnonymized;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Anonimização do devedor (LGPD): a nota e o motivo de encerramento dos processos de insolvência são texto livre e
 * podem citar pessoas (sócios, administrador judicial); são neutralizados. O número, o juízo e as datas do processo —
 * dados públicos da empresa — permanecem para a rastreabilidade da suspensão da cobrança.
 */
@Component
class InsolvencyAnonymizationListener extends TenantEventListener {

    private final InsolvencyService insolvency;

    InsolvencyAnonymizationListener(InsolvencyService insolvency) {
        this.insolvency = insolvency;
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(DebtorAnonymized event) {
        handleOnce(event, "debt.insolvency-anonymized", () -> insolvency.forgetFreeText(event.debtorId()));
    }
}
