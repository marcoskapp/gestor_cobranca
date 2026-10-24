package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.communication.MessageStatusChanged;
import br.com.gestorcobranca.shared.events.debt.DebtorAnonymized;
import br.com.gestorcobranca.shared.events.negotiation.AgreementAttributionRecorded;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Consumidores do módulo (idempotentes por evento):
 * <ul>
 *   <li>{@code MessageStatusChanged} INBOUND/RECEIVED → agenda o turno da conversa (o job decide pelo dono
 *       e pela política);</li>
 *   <li>{@code AgreementAttributionRecorded} / {@code AgreementStatusChanged} → fatos de acordo para as
 *       métricas de qualidade;</li>
 *   <li>{@code DebtorAnonymized} → descarte do conteúdo da trilha e dos comentários de revisão.</li>
 * </ul>
 */
@Component
class AssistantEventListeners extends TenantEventListener {

    private final AssistantReactions reactions;

    AssistantEventListeners(AssistantReactions reactions) {
        this.reactions = reactions;
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(MessageStatusChanged event) {
        if ("INBOUND".equals(event.direction()) && "RECEIVED".equals(event.status())) {
            handleOnce(event, "assistant.message-received", () -> reactions.messageReceived(event));
        }
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(AgreementAttributionRecorded event) {
        handleOnce(event, "assistant.agreement-attribution", () -> reactions.attribution(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(AgreementStatusChanged event) {
        handleOnce(event, "assistant.agreement-status", () -> reactions.agreementStatus(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(DebtorAnonymized event) {
        handleOnce(event, "assistant.debtor-anonymized", () -> reactions.anonymized(event));
    }
}
