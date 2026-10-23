package br.com.gestorcobranca.payment.infrastructure.persistence;

import br.com.gestorcobranca.payment.domain.model.PaymentInstruction;
import br.com.gestorcobranca.payment.domain.repository.PaymentInstructionRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface PaymentInstructionJpaRepository extends JpaRepository<PaymentInstruction, UUID>,
        JpaSpecificationExecutor<PaymentInstruction>, PaymentInstructionRepository {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from PaymentInstruction i where i.id = :id")
    Optional<PaymentInstruction> lockById(@Param("id") UUID id);

    @Override
    default Optional<PaymentInstruction> findByIdForUpdate(UUID id) {
        return lockById(id);
    }

    @Query("""
            select i from PaymentInstruction i
             where i.status in (br.com.gestorcobranca.payment.domain.model.InstructionStatus.PENDING,
                                br.com.gestorcobranca.payment.domain.model.InstructionStatus.ACTIVE)
               and i.expiresAt <= :now
             order by i.expiresAt, i.id
            """)
    List<PaymentInstruction> openExpired(@Param("now") Instant now, Limit limit);

    @Override
    default List<PaymentInstruction> findOpenExpiredBefore(Instant now, int limit) {
        return openExpired(now, Limit.of(limit));
    }

    @Override
    default Page<PaymentInstruction> search(Criteria c, Pageable pageable) {
        Specification<PaymentInstruction> spec = Specification.unrestricted();
        if (c.creditorIds() != null) {
            spec = spec.and((root, query, cb) -> c.creditorIds().isEmpty() ? cb.disjunction()
                    : root.get("creditorId").in(c.creditorIds()));
        }
        if (c.creditorId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("creditorId"), c.creditorId()));
        }
        if (c.debtorId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("debtorId"), c.debtorId()));
        }
        if (c.agreementId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("agreementId"), c.agreementId()));
        }
        if (c.titleId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("titleId"), c.titleId()));
        }
        if (c.statuses() != null && !c.statuses().isEmpty()) {
            spec = spec.and((root, query, cb) -> root.get("status").in(c.statuses()));
        }
        return findAll(spec, pageable);
    }
}
