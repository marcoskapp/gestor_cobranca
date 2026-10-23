package br.com.gestorcobranca.legal.infrastructure.persistence;

import br.com.gestorcobranca.legal.domain.model.ViabilityAssessment;
import br.com.gestorcobranca.legal.domain.repository.ViabilityAssessmentRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface ViabilityAssessmentJpaRepository extends JpaRepository<ViabilityAssessment, UUID>,
        ViabilityAssessmentRepository {
}
