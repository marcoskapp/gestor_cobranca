package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.identity.TenantProvisioned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Consumidores de eventos de integração do módulo, idempotentes por evento ({@code shared.consumed_event}). */
@Component
class DebtEventListeners extends TenantEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(DebtEventListeners.class);

    private final PrescriptionRuleService prescriptionRules;

    DebtEventListeners(PrescriptionRuleService prescriptionRules) {
        this.prescriptionRules = prescriptionRules;
    }

    /** Nova assessoria recebe a tabela de mercado de prescrição, pendente de validação jurídica. */
    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(TenantProvisioned event) {
        handleOnce(event, "debt.prescription-defaults", () -> {
            int created = prescriptionRules.applyMarketDefaults().size();
            LOG.atInfo().addKeyValue("rulesCreated", created).log("Regras de prescrição de mercado semeadas na assessoria");
        });
    }
}
