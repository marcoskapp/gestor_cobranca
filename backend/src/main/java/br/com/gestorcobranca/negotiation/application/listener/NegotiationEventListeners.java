package br.com.gestorcobranca.negotiation.application.listener;

import br.com.gestorcobranca.negotiation.application.port.in.AuthorityProvisioning;
import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.identity.TenantProvisioned;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Consumidores de eventos de integração do módulo (idempotentes por {@code shared.consumed_event}). */
@Component
class NegotiationEventListeners extends TenantEventListener {

    private final AuthorityProvisioning provisioning;

    NegotiationEventListeners(AuthorityProvisioning provisioning) {
        this.provisioning = provisioning;
    }

    /** Perfis de alçada padrão semeados na nova assessoria. */
    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(TenantProvisioned event) {
        handleOnce(event, "negotiation.tenant-provisioned", provisioning::ensureDefaultProfiles);
    }
}
