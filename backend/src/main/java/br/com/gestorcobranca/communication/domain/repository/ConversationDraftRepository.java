package br.com.gestorcobranca.communication.domain.repository;

import br.com.gestorcobranca.communication.domain.model.ConversationDraft;
import br.com.gestorcobranca.communication.domain.model.DraftStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationDraftRepository {

    ConversationDraft save(ConversationDraft draft);

    Optional<ConversationDraft> findById(UUID id);

    List<ConversationDraft> findByConversationIdAndStatusOrderByCreatedAtDesc(UUID conversationId, DraftStatus status);
}
