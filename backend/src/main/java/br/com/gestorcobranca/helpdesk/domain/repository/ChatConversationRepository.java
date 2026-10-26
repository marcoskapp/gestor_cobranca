package br.com.gestorcobranca.helpdesk.domain.repository;

import br.com.gestorcobranca.helpdesk.domain.model.ChatConversation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatConversationRepository {

    ChatConversation save(ChatConversation conversation);

    Optional<ChatConversation> findById(UUID id);

    /** Com bloqueio de linha: perguntas simultâneas na mesma conversa são serializadas. */
    Optional<ChatConversation> findByIdForUpdate(UUID id);

    /** Mais recentes primeiro. */
    List<ChatConversation> findTop20ByUserIdOrderByLastActivityAtDesc(UUID userId);
}
