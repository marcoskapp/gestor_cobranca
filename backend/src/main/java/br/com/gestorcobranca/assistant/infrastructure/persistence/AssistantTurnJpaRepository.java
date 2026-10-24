package br.com.gestorcobranca.assistant.infrastructure.persistence;

import br.com.gestorcobranca.assistant.domain.model.AssistantTurn;
import br.com.gestorcobranca.assistant.domain.repository.AssistantTurnRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface AssistantTurnJpaRepository extends JpaRepository<AssistantTurn, UUID>, AssistantTurnRepository {

    @Override
    @Query(value = """
            select distinct handoff_reason from assistant.assistant_turn
             where session_id = :sessionId and started_at >= :since and handoff_reason is not null
            """, nativeQuery = true)
    List<String> handoffReasonsSince(UUID sessionId, Instant since);

    @Override
    @Query(value = """
            select count(*) from assistant.assistant_turn
             where session_id = :sessionId and started_at >= :since and jsonb_array_length(guardrails) > 0
            """, nativeQuery = true)
    long countGuardrailsSince(UUID sessionId, Instant since);
}
