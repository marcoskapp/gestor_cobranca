package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.CaseAssignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface CaseAssignmentRepository {

    CaseAssignment save(CaseAssignment assignment);

    Page<CaseAssignment> findByCaseIdOrderByOccurredAtDesc(UUID caseId, Pageable pageable);
}
