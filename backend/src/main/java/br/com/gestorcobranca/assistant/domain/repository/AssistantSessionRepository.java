package br.com.gestorcobranca.assistant.domain.repository;

import br.com.gestorcobranca.assistant.domain.model.AssistantSession;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssistantSessionRepository {

    AssistantSession save(AssistantSession session);

    AssistantSession saveAndFlush(AssistantSession session);

    Optional<AssistantSession> findById(UUID id);

    Optional<AssistantSession> findByConversationId(UUID conversationId);

    List<AssistantSession> findByDebtorId(UUID debtorId);

    /** Sessões com turno interrompido (instância caiu antes do registro). */
    List<AssistantSession> findByPendingSinceBefore(Instant before);
}
