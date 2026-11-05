package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.DisputeAssignment;
import br.com.gestorcobranca.collection.domain.repository.DisputeAssignmentRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

interface DisputeAssignmentJpaRepository extends JpaRepository<DisputeAssignment, UUID>, DisputeAssignmentRepository {

    @Override
    default Map<UUID, Instant> lastAssignedAt(Collection<UUID> assigneeIds) {
        if (assigneeIds == null || assigneeIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Instant> result = new HashMap<>();
        for (Object[] row : lastAssignedRows(assigneeIds)) {
            result.put((UUID) row[0], (Instant) row[1]);
        }
        return result;
    }

    @Query("""
            select a.assigneeId, max(a.assignedAt) from DisputeAssignment a
             where a.assigneeId in :assigneeIds group by a.assigneeId
            """)
    List<Object[]> lastAssignedRows(Collection<UUID> assigneeIds);
}
