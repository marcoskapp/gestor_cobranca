package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.compliance.ContactPolicyPublished;
import br.com.gestorcobranca.shared.events.compliance.ContactRestrictionChanged;
import br.com.gestorcobranca.shared.events.debt.PortfolioLoaded;
import br.com.gestorcobranca.shared.events.debt.TitleStatusChanged;
import br.com.gestorcobranca.shared.events.identity.TenantProvisioned;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.events.organization.PortfolioStatusChanged;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.events.payment.PaymentReversed;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Consumidores dos eventos de integração (linguagem publicada em {@code shared.events}).
 * Idempotentes por evento ({@code shared.consumed_event}); a sincronização de casos é também
 * idempotente pelo estado (sincronizar sem mudança não gera efeito), de modo que a ordem de
 * chegada de eventos do mesmo devedor não importa: cada consumo lê a posição atual dos títulos.
 */
@Component
class CollectionEventListeners extends TenantEventListener {

    private final CollectionReactions reactions;
    private final PromiseService promises;

    CollectionEventListeners(CollectionReactions reactions, PromiseService promises) {
        this.reactions = reactions;
        this.promises = promises;
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(TitleStatusChanged event) {
        handleOnce(event, "collection.title-status", () -> reactions.titleStatusChanged(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PortfolioLoaded event) {
        handleOnce(event, "collection.portfolio-loaded", () -> reactions.portfolioLoaded(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PortfolioStatusChanged event) {
        handleOnce(event, "collection.portfolio-status", () -> reactions.portfolioStatusChanged(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(ContactRestrictionChanged event) {
        handleOnce(event, "collection.contact-restriction", () -> reactions.contactRestrictionChanged(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(ContactPolicyPublished event) {
        handleOnce(event, "collection.contact-policy", () -> reactions.contactPolicyPublished(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PaymentConfirmed event) {
        handleOnce(event, "collection.payment-confirmed", () -> promises.paymentConfirmed(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PaymentReversed event) {
        handleOnce(event, "collection.payment-reversed", () -> promises.paymentReversed(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(AgreementStatusChanged event) {
        handleOnce(event, "collection.agreement-status", () -> reactions.agreementStatusChanged(event));
    }

    /** Catálogo padrão de tabulações na nova assessoria. */
    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(TenantProvisioned event) {
        handleOnce(event, "collection.tenant-provisioned", reactions::tenantProvisioned);
    }
}
