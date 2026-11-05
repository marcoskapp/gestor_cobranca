package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.DisputeDeadlineRule;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisputeDeadlineRuleRepository {

    DisputeDeadlineRule save(DisputeDeadlineRule rule);

    Optional<DisputeDeadlineRule> findById(UUID id);

    List<DisputeDeadlineRule> findByActiveTrue();
}
