package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.domain.model.statement.CommissionStatement;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CommissionStatementJpa extends JpaRepository<CommissionStatement, UUID>,
        JpaSpecificationExecutor<CommissionStatement> {

    Optional<CommissionStatement> findByPeriodStartAndBeneficiaryId(LocalDate periodStart, UUID beneficiaryId);

    List<CommissionStatement> findByPeriodStart(LocalDate periodStart);

    List<CommissionStatement> findByPeriodStartAndStatusIn(LocalDate periodStart, Collection<StatementStatus> statuses);

    static Specification<CommissionStatement> filter(LocalDate periodStart, Collection<UUID> beneficiaryIds,
                                                     StatementStatus status) {
        return (root, query, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (periodStart != null) {
                predicates.add(cb.equal(root.get("periodStart"), periodStart));
            }
            if (beneficiaryIds != null) {
                predicates.add(beneficiaryIds.isEmpty() ? cb.disjunction() : root.get("beneficiaryId").in(beneficiaryIds));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }
}
