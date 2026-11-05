package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.DisputeCreditorRequest;
import br.com.gestorcobranca.collection.domain.repository.DisputeCreditorRequestRepository;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface DisputeCreditorRequestJpaRepository extends JpaRepository<DisputeCreditorRequest, UUID>,
        DisputeCreditorRequestRepository {

    @Override
    default List<DisputeCreditorRequest> findFollowUpCandidates(LocalDate reminderUntil, LocalDate today, int limit) {
        return followUpCandidates(reminderUntil, today, Limit.of(limit));
    }

    /** Índice {@code ix_dispute_request_followup (tenant_id, requested_due_date) where status = 'SENT'}. */
    @Query("""
            select r from DisputeCreditorRequest r
             where r.status = br.com.gestorcobranca.collection.domain.model.CreditorRequestStatus.SENT
               and ((r.reminderQueuedAt is null and r.requestedDueDate <= :reminderUntil)
                    or (r.overdue = false and r.requestedDueDate < :today))
             order by r.requestedDueDate, r.id
            """)
    List<DisputeCreditorRequest> followUpCandidates(LocalDate reminderUntil, LocalDate today, Limit limit);
}
