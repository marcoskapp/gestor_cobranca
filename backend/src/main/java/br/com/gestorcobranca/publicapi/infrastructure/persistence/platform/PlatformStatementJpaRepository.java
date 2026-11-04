package br.com.gestorcobranca.publicapi.infrastructure.persistence.platform;

import br.com.gestorcobranca.publicapi.domain.model.platform.PlatformStatement;
import br.com.gestorcobranca.publicapi.domain.repository.platform.PlatformStatementRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

interface PlatformStatementJpaRepository extends JpaRepository<PlatformStatement, UUID>,
        JpaSpecificationExecutor<PlatformStatement>, PlatformStatementRepository {

    Optional<PlatformStatement> findFirstByPeriodStartOrderByRevisionDesc(LocalDate periodStart);

    Optional<PlatformStatement> findFirstByOrderByPeriodEndDescRevisionDesc();

    @Override
    default Optional<PlatformStatement> findLatest(LocalDate periodStart) {
        return findFirstByPeriodStartOrderByRevisionDesc(periodStart);
    }

    @Override
    default Optional<PlatformStatement> findLastCycle() {
        return findFirstByOrderByPeriodEndDescRevisionDesc();
    }

    @Override
    default boolean existsAny() {
        return count() > 0;
    }

    @Override
    default Page<PlatformStatement> search(LocalDate closedFrom, LocalDate closedTo, Pageable pageable) {
        Specification<PlatformStatement> spec = Specification.unrestricted();
        if (closedFrom != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("periodEnd"), closedFrom));
        }
        if (closedTo != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("periodEnd"), closedTo));
        }
        return findAll(spec, pageable);
    }
}
