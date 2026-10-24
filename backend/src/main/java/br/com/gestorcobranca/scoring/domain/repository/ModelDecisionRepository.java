package br.com.gestorcobranca.scoring.domain.repository;

import br.com.gestorcobranca.scoring.domain.model.ModelDecision;

import java.util.List;
import java.util.UUID;

public interface ModelDecisionRepository {

    ModelDecision save(ModelDecision decision);

    List<ModelDecision> findAllByOrderByDecidedAtDesc();

    List<ModelDecision> findByModelIdOrderByDecidedAtDesc(UUID modelId);
}
