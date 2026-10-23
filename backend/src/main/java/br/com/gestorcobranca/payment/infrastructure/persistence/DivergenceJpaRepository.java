package br.com.gestorcobranca.payment.infrastructure.persistence;

import br.com.gestorcobranca.payment.domain.model.Divergence;
import br.com.gestorcobranca.payment.domain.model.DivergenceStatus;
import br.com.gestorcobranca.payment.domain.model.DivergenceType;
import br.com.gestorcobranca.payment.domain.repository.DivergenceRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

interface DivergenceJpaRepository extends JpaRepository<Divergence, UUID>, JpaSpecificationExecutor<Divergence>,
        DivergenceRepository {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Divergence d where d.id = :id")
    Optional<Divergence> lockById(@Param("id") UUID id);

    @Override
    default Optional<Divergence> findByIdForUpdate(UUID id) {
        return lockById(id);
    }

    @Override
    default Page<Divergence> search(Set<UUID> creditorIds, Set<DivergenceStatus> statuses, DivergenceType type,
                                    Pageable pageable) {
        Specification<Divergence> spec = Specification.unrestricted();
        if (creditorIds != null) {
            spec = spec.and((root, query, cb) -> creditorIds.isEmpty() ? cb.disjunction()
                    : root.get("creditorId").in(creditorIds));
        }
        if (statuses != null && !statuses.isEmpty()) {
            spec = spec.and((root, query, cb) -> root.get("status").in(statuses));
        }
        if (type != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("type"), type));
        }
        return findAll(spec, pageable);
    }
}
