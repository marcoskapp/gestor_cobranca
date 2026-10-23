package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.CaseAssignment;
import br.com.gestorcobranca.collection.domain.repository.CaseAssignmentRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface CaseAssignmentJpaRepository extends JpaRepository<CaseAssignment, UUID>, CaseAssignmentRepository {
}
