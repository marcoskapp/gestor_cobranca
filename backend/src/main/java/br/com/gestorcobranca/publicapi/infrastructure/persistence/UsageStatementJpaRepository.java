package br.com.gestorcobranca.publicapi.infrastructure.persistence;

import br.com.gestorcobranca.publicapi.domain.model.UsageStatement;
import br.com.gestorcobranca.publicapi.domain.repository.UsageStatementRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

interface UsageStatementJpaRepository extends JpaRepository<UsageStatement, UUID>,
        JpaSpecificationExecutor<UsageStatement>, UsageStatementRepository {

    Optional<UsageStatement> findFirstByCreditorIdAndPeriodStartOrderByRevisionDesc(UUID creditorId, LocalDate periodStart);

    Optional<UsageStatement> findFirstByCreditorIdOrderByPeriodEndDescRevisionDesc(UUID creditorId);

    @Override
    default Optional<UsageStatement> findLatest(UUID creditorId, LocalDate periodStart) {
        return findFirstByCreditorIdAndPeriodStartOrderByRevisionDesc(creditorId, periodStart);
    }

    @Override
    default Optional<UsageStatement> findLastCycle(UUID creditorId) {
        return findFirstByCreditorIdOrderByPeriodEndDescRevisionDesc(creditorId);
    }

    @Override
    default Page<UsageStatement> search(Collection<UUID> allowedCreditorIds, UUID creditorId, LocalDate closedFrom,
                                        LocalDate closedTo, Pageable pageable) {
        Specification<UsageStatement> spec = Specification.unrestricted();
        if (allowedCreditorIds != null) {
            spec = spec.and((root, q, cb) -> allowedCreditorIds.isEmpty()
                    ? cb.disjunction() : root.get("creditorId").in(allowedCreditorIds));
        }
        if (creditorId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("creditorId"), creditorId));
        }
        if (closedFrom != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("periodEnd"), closedFrom));
        }
        if (closedTo != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("periodEnd"), closedTo));
        }
        return findAll(spec, pageable);
    }
}
