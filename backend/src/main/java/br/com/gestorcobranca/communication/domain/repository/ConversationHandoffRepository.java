package br.com.gestorcobranca.communication.domain.repository;

import br.com.gestorcobranca.communication.domain.model.ConversationHandoff;

import java.util.List;
import java.util.UUID;

public interface ConversationHandoffRepository {

    ConversationHandoff save(ConversationHandoff handoff);

    List<ConversationHandoff> findByConversationIdOrderByOccurredAtAsc(UUID conversationId);
}
