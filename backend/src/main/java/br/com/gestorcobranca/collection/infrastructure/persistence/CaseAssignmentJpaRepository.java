package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.CaseAssignment;
import br.com.gestorcobranca.collection.domain.repository.CaseAssignmentRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface CaseAssignmentJpaRepository extends JpaRepository<CaseAssignment, UUID>, CaseAssignmentRepository {

    @Override
    @Query("select a from CaseAssignment a where a.caseId = :caseId and a.validTo is null")
    Optional<CaseAssignment> findCurrentByCaseId(UUID caseId);

    @Override
    @Query("""
            select a from CaseAssignment a
             where a.caseId = :caseId and a.validFrom <= :at and (a.validTo is null or a.validTo > :at)
            """)
    Optional<CaseAssignment> findAt(UUID caseId, Instant at);
}
