package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.CaseAssignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CaseAssignmentRepository {

    CaseAssignment save(CaseAssignment assignment);

    /** Grava imediatamente (o fim de vigência precede a abertura do período seguinte). */
    CaseAssignment saveAndFlush(CaseAssignment assignment);

    /** Período vigente (sem fim) do caso. */
    Optional<CaseAssignment> findCurrentByCaseId(UUID caseId);

    /** Período vigente no instante: {@code validFrom <= at < validTo}. */
    Optional<CaseAssignment> findAt(UUID caseId, Instant at);

    Page<CaseAssignment> findByCaseIdOrderByValidFromDesc(UUID caseId, Pageable pageable);
}
