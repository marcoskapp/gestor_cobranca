package br.com.gestorcobranca.scoring.infrastructure.persistence;

import br.com.gestorcobranca.scoring.domain.model.ModelDecision;
import br.com.gestorcobranca.scoring.domain.repository.ModelDecisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface ModelDecisionJpaRepository extends JpaRepository<ModelDecision, UUID>, ModelDecisionRepository {

    @Override
    List<ModelDecision> findAllByOrderByDecidedAtDesc();

    @Override
    List<ModelDecision> findByModelIdOrderByDecidedAtDesc(UUID modelId);
}
