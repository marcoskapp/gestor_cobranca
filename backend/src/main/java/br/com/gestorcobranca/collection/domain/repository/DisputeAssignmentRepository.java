package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.DisputeAssignment;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DisputeAssignmentRepository {

    DisputeAssignment save(DisputeAssignment assignment);

    List<DisputeAssignment> findByDisputeIdOrderByAssignedAtAsc(UUID disputeId);

    /** Instante da última contestação atribuída a cada analista (ausente = nunca recebeu). */
    Map<UUID, Instant> lastAssignedAt(Collection<UUID> assigneeIds);
}
