package br.com.gestorcobranca.payment.infrastructure.persistence;

import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.payment.domain.model.PaymentMethod;
import br.com.gestorcobranca.payment.domain.model.PaymentSource;
import br.com.gestorcobranca.payment.domain.repository.PaymentRepository;
import br.com.gestorcobranca.shared.domain.Money;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

interface PaymentJpaRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment>, PaymentRepository {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> lockById(@Param("id") UUID id);

    @Override
    default Optional<Payment> findByIdForUpdate(UUID id) {
        return lockById(id);
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.providerTransactionId = :transactionId")
    Optional<Payment> lockByProviderTransactionId(@Param("transactionId") String transactionId);

    @Override
    default Optional<Payment> findByProviderTransactionIdForUpdate(String providerTransactionId) {
        return lockByProviderTransactionId(providerTransactionId);
    }

    @Query(value = """
            select coalesce(sum(amount), 0) from payment.payment
             where source = 'EXTERNAL' and not review_required and status <> 'REJECTED'
               and creditor_id = :creditorId and debtor_id = :debtorId and informed_at >= :since
            """, nativeQuery = true)
    BigDecimal sumExternalForDebtor(@Param("creditorId") UUID creditorId, @Param("debtorId") UUID debtorId,
                                    @Param("since") Instant since);

    @Query(value = """
            select coalesce(sum(amount), 0) from payment.payment
             where source = 'EXTERNAL' and not review_required and status <> 'REJECTED' and informed_by = :informedBy and informed_at >= :since
            """, nativeQuery = true)
    BigDecimal sumExternalByInformer(@Param("informedBy") UUID informedBy, @Param("since") Instant since);

    @Override
    default Money sumExternalInformedForDebtor(UUID creditorId, UUID debtorId, Instant since) {
        return Money.ofNullable(sumExternalForDebtor(creditorId, debtorId, since));
    }

    @Override
    default Money sumExternalInformedBy(UUID informedBy, Instant since) {
        return Money.ofNullable(sumExternalByInformer(informedBy, since));
    }

    @Override
    default Page<Payment> search(Criteria c, Pageable pageable) {
        Specification<Payment> spec = Specification.unrestricted();
        if (c.creditorIds() != null) {
            Set<UUID> allowed = c.creditorIds();
            spec = spec.and((root, query, cb) -> allowed.isEmpty() ? cb.disjunction() : root.get("creditorId").in(allowed));
        }
        if (c.creditorId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("creditorId"), c.creditorId()));
        }
        if (c.debtorId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("debtorId"), c.debtorId()));
        }
        if (c.statuses() != null && !c.statuses().isEmpty()) {
            spec = spec.and((root, query, cb) -> root.get("status").in(c.statuses()));
        }
        if (c.source() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("source"), PaymentSource.valueOf(c.source())));
        }
        if (c.method() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("method"), PaymentMethod.valueOf(c.method())));
        }
        if (c.paidFrom() != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("paidAt"), c.paidFrom()));
        }
        if (c.paidTo() != null) {
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("paidAt"), c.paidTo()));
        }
        if (c.withBalance()) {
            spec = spec.and((root, query, cb) -> cb.notEqual(root.get("allocatedAmount"), root.get("amount")));
        }
        if (c.excludeInformedBy() != null) {
            spec = spec.and((root, query, cb) -> cb.or(cb.isNull(root.get("informedBy")),
                    cb.notEqual(root.get("informedBy"), c.excludeInformedBy())));
        }
        return findAll(spec, pageable);
    }

    @Query(value = """
            select p.id from payment.payment p
             where p.status in ('CONFIRMED', 'RECONCILED')
               and (p.allocated_amount < p.amount or exists (select 1 from payment.payment_allocation a
                     where a.payment_id = p.id and a.status = 'ACTIVE' and a.unapplied_amount > a.released_amount))
             order by p.paid_at, p.id
            """, countQuery = """
            select count(*) from payment.payment p
             where p.status in ('CONFIRMED', 'RECONCILED')
               and (p.allocated_amount < p.amount or exists (select 1 from payment.payment_allocation a
                     where a.payment_id = p.id and a.status = 'ACTIVE' and a.unapplied_amount > a.released_amount))
            """, nativeQuery = true)
    Page<UUID> pendingReallocationAll(Pageable pageable);

    @Query(value = """
            select p.id from payment.payment p
             where p.status in ('CONFIRMED', 'RECONCILED') and p.creditor_id in (:creditors)
               and (p.allocated_amount < p.amount or exists (select 1 from payment.payment_allocation a
                     where a.payment_id = p.id and a.status = 'ACTIVE' and a.unapplied_amount > a.released_amount))
             order by p.paid_at, p.id
            """, countQuery = """
            select count(*) from payment.payment p
             where p.status in ('CONFIRMED', 'RECONCILED') and p.creditor_id in (:creditors)
               and (p.allocated_amount < p.amount or exists (select 1 from payment.payment_allocation a
                     where a.payment_id = p.id and a.status = 'ACTIVE' and a.unapplied_amount > a.released_amount))
            """, nativeQuery = true)
    Page<UUID> pendingReallocationIn(@Param("creditors") Collection<UUID> creditors, Pageable pageable);

    @Override
    default Page<UUID> findPendingReallocationIds(Set<UUID> creditorIds, Pageable pageable) {
        if (creditorIds == null) {
            return pendingReallocationAll(pageable);
        }
        return creditorIds.isEmpty() ? Page.empty(pageable) : pendingReallocationIn(creditorIds, pageable);
    }
}
