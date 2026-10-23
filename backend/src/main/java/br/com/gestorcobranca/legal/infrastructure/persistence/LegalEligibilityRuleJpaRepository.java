package br.com.gestorcobranca.legal.infrastructure.persistence;

import br.com.gestorcobranca.legal.domain.model.LegalEligibilityRule;
import br.com.gestorcobranca.legal.domain.repository.LegalEligibilityRuleRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface LegalEligibilityRuleJpaRepository extends JpaRepository<LegalEligibilityRule, UUID>,
        LegalEligibilityRuleRepository {

    @Override
    Optional<LegalEligibilityRule> findById(UUID id);
}
