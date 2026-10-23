package br.com.gestorcobranca.legal.domain.repository;

import br.com.gestorcobranca.legal.domain.model.ViabilityAssessment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ViabilityAssessmentRepository {

    ViabilityAssessment save(ViabilityAssessment assessment);

    Optional<ViabilityAssessment> findFirstByMatterIdOrderByCreatedAtDesc(UUID matterId);

    List<ViabilityAssessment> findByMatterIdOrderByCreatedAtDesc(UUID matterId);
}
