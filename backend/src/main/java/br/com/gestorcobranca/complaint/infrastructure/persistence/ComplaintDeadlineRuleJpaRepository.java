package br.com.gestorcobranca.complaint.infrastructure.persistence;

import br.com.gestorcobranca.complaint.domain.model.ComplaintDeadlineRule;
import br.com.gestorcobranca.complaint.domain.repository.ComplaintDeadlineRuleRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface ComplaintDeadlineRuleJpaRepository extends JpaRepository<ComplaintDeadlineRule, UUID>,
        ComplaintDeadlineRuleRepository {
}
