package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.collection.CaseAssignmentChanged;
import br.com.gestorcobranca.shared.events.collection.CaseStatusChanged;
import br.com.gestorcobranca.shared.events.communication.HandoffRequested;
import br.com.gestorcobranca.shared.events.communication.MessageStatusChanged;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Consumidores dos eventos de integração, idempotentes por evento ({@code shared.consumed_event}).
 * Nomes de consumidor estáveis: renomear faz eventos já consumidos serem reprocessados.
 */
@Component
class CommunicationEventListeners extends TenantEventListener {

    private final CommunicationReactions reactions;

    CommunicationEventListeners(CommunicationReactions reactions) {
        this.reactions = reactions;
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(MessageStatusChanged event) {
        handleOnce(event, "communication.message-interaction", () -> reactions.messageStatusChanged(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(HandoffRequested event) {
        handleOnce(event, "communication.handoff-requested", () -> reactions.handoffRequested(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(CaseAssignmentChanged event) {
        handleOnce(event, "communication.case-assignment", () -> reactions.caseAssignmentChanged(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(CaseStatusChanged event) {
        handleOnce(event, "communication.case-status", () -> reactions.caseStatusChanged(event));
    }
}
