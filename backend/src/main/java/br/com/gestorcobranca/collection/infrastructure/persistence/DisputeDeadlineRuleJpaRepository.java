package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.DisputeDeadlineRule;
import br.com.gestorcobranca.collection.domain.repository.DisputeDeadlineRuleRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface DisputeDeadlineRuleJpaRepository extends JpaRepository<DisputeDeadlineRule, UUID>, DisputeDeadlineRuleRepository {
}
