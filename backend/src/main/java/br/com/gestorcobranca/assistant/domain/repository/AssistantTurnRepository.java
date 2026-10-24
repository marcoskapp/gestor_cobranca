package br.com.gestorcobranca.assistant.domain.repository;

import br.com.gestorcobranca.assistant.domain.model.AssistantTurn;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssistantTurnRepository {

    AssistantTurn save(AssistantTurn turn);

    Optional<AssistantTurn> findById(UUID id);

    Page<AssistantTurn> findByConversationIdOrderByStartedAtDesc(UUID conversationId, Pageable pageable);

    List<AssistantTurn> findByDebtorId(UUID debtorId);

    /** Motivos de transferência registrados nos turnos da sessão desde o instante. */
    List<String> handoffReasonsSince(UUID sessionId, Instant since);

    /** Turnos da sessão com guardrail acionado desde o instante. */
    long countGuardrailsSince(UUID sessionId, Instant since);
}
