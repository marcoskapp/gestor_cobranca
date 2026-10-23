package br.com.gestorcobranca.legal.domain.repository;

import br.com.gestorcobranca.legal.domain.model.LegalEligibilityRule;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LegalEligibilityRuleRepository {

    LegalEligibilityRule save(LegalEligibilityRule rule);

    Optional<LegalEligibilityRule> findById(UUID id);

    List<LegalEligibilityRule> findAllByOrderByCreatedAtAsc();

    List<LegalEligibilityRule> findByActiveTrueOrderByCreatedAtAsc();

    List<LegalEligibilityRule> findByCreditorIdAndActiveTrueOrderByCreatedAtAsc(UUID creditorId);
}
