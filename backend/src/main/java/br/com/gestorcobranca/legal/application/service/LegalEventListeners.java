package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.campaign.CadenceExhausted;
import br.com.gestorcobranca.shared.events.debt.TitlePrescriptionApproaching;
import br.com.gestorcobranca.shared.events.debt.TitleStatusChanged;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Consumidores idempotentes dos eventos de integração usados pelo jurídico (ver {@link LegalReactions}). */
@Component
class LegalEventListeners extends TenantEventListener {

    private final LegalReactions reactions;

    LegalEventListeners(LegalReactions reactions) {
        this.reactions = reactions;
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(AgreementStatusChanged event) {
        handleOnce(event, "legal.agreement-status", () -> reactions.agreementStatusChanged(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(TitleStatusChanged event) {
        handleOnce(event, "legal.title-status", () -> reactions.titleStatusChanged(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(CadenceExhausted event) {
        handleOnce(event, "legal.cadence-exhausted", () -> reactions.cadenceExhausted(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(TitlePrescriptionApproaching event) {
        handleOnce(event, "legal.prescription-approaching", () -> reactions.prescriptionApproaching(event));
    }
}
