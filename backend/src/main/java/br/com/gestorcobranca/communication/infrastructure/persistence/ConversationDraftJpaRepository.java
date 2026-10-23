package br.com.gestorcobranca.communication.infrastructure.persistence;

import br.com.gestorcobranca.communication.domain.model.ConversationDraft;
import br.com.gestorcobranca.communication.domain.model.DraftStatus;
import br.com.gestorcobranca.communication.domain.repository.ConversationDraftRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface ConversationDraftJpaRepository extends JpaRepository<ConversationDraft, UUID>, ConversationDraftRepository {

    @Override
    List<ConversationDraft> findByConversationIdAndStatusOrderByCreatedAtDesc(UUID conversationId, DraftStatus status);
}
