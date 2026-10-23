package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.domain.model.Conversation;
import br.com.gestorcobranca.communication.domain.model.OwnerType;
import br.com.gestorcobranca.communication.domain.model.SenderType;
import br.com.gestorcobranca.communication.domain.repository.ConversationRepository;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Escopo e regras de trabalho das conversas (mesma semântica do collection para o caso):
 * leitura fora do escopo → 404; comando → 403; conversa sem credor (contato não identificado em
 * conta da assessoria) exige acesso a todos os credores.
 */
@Component
class ConversationAccess {

    private final ConversationRepository conversations;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;

    ConversationAccess(ConversationRepository conversations, AccessScope accessScope, CurrentActor currentActor) {
        this.conversations = conversations;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
    }

    Conversation visible(UUID conversationId) {
        Conversation c = conversations.findById(conversationId)
                .orElseThrow(() -> ResourceNotFoundException.of("conversation", conversationId));
        requireVisible(c);
        return c;
    }

    /** Para comandos: visível e credor no escopo; lock pessimista da linha (mensagens simultâneas). */
    Conversation forCommand(UUID conversationId) {
        Conversation c = conversations.findByIdForUpdate(conversationId)
                .orElseThrow(() -> ResourceNotFoundException.of("conversation", conversationId));
        requireVisible(c);
        if (c.getCreditorId() == null) {
            accessScope.requireAllCreditors("conversation");
        } else {
            accessScope.requireCreditor(c.getCreditorId());
        }
        return c;
    }

    void requireVisible(Conversation c) {
        if (c.getCreditorId() == null) {
            if (!accessScope.hasAllCreditors()) {
                throw ResourceNotFoundException.of("conversation", c.getId());
            }
        } else {
            accessScope.requireVisible("conversation", c.getId(), c.getCreditorId());
        }
    }

    /** Credores permitidos para listagens (nulo = sem restrição). */
    Set<UUID> creditorFilter() {
        return accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(null);
    }

    /**
     * Exige que um operador possa trabalhar a conversa agora.
     *
     * @return true se o operador deve assumi-la (estava na fila, sem dono ou com o assistente)
     */
    boolean requireWorkable(Conversation c) {
        Actor actor = currentActor.get();
        if (!actor.isUser()) {
            return false;
        }
        if (c.getOwnerType() == OwnerType.USER) {
            if (actor.id().equals(c.getOwnerUserId()) || actor.has(Permission.CASE_ASSIGN)) {
                return false;
            }
            throw new ConflictException("conversation.assigned_to_other", "A conversa está com outro operador");
        }
        return true;
    }

    Actor actor() {
        return currentActor.get();
    }

    /** Autor de uma mensagem de saída a partir do ator corrente. */
    SenderType senderOf(UUID campaignExecutionId) {
        Actor actor = currentActor.get();
        return switch (actor.type()) {
            case USER -> SenderType.USER;
            case ASSISTANT -> SenderType.ASSISTANT;
            case SYSTEM, INTEGRATION -> campaignExecutionId != null ? SenderType.CAMPAIGN : SenderType.SYSTEM;
            case DEBTOR -> throw new ConflictException("message.sender_invalid", "O devedor não envia por este canal");
        };
    }
}
