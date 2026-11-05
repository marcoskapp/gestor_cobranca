package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.shared.paging.ChangeQuery;
import br.com.gestorcobranca.shared.paging.ChangeSpecifications;

import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DebtorNoticeStatus;
import br.com.gestorcobranca.collection.domain.model.DisputeAdjustmentStatus;
import br.com.gestorcobranca.collection.domain.repository.DisputeRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeSearchCriteria;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;

interface DisputeJpaRepository extends JpaRepository<Dispute, UUID>, JpaSpecificationExecutor<Dispute>,
        DisputeRepository {

    @Override
    @Query("""
            select case when count(d) > 0 then true else false end from Dispute d
             where d.caseId = :caseId and d.blocksCollection
               and d.status in (br.com.gestorcobranca.collection.domain.model.DisputeStatus.OPEN,
                                br.com.gestorcobranca.collection.domain.model.DisputeStatus.UNDER_REVIEW)
            """)
    boolean existsBlockingByCaseId(UUID caseId);

    @Override
    default List<Dispute> findOverdueCandidates(LocalDate today, int limit) {
        return overdueCandidates(today, Limit.of(limit));
    }

    @Query("""
            select d from Dispute d
             where d.status in (br.com.gestorcobranca.collection.domain.model.DisputeStatus.OPEN,
                                br.com.gestorcobranca.collection.domain.model.DisputeStatus.UNDER_REVIEW)
               and d.overdue = false and d.responseDueDate < :today
             order by d.responseDueDate, d.id
            """)
    List<Dispute> overdueCandidates(LocalDate today, Limit limit);

    @Override
    default List<Dispute> findChanges(UUID creditorId, ChangeQuery query) {
        return findAll(ChangeSpecifications.<Dispute>window(query, List.of(creditorId)), ChangeSpecifications.page(query))
                .getContent();
    }

    @Override
    default Page<Dispute> search(DisputeSearchCriteria c, Pageable pageable) {
        Specification<Dispute> spec = Specification.unrestricted();
        if (c.allowedCreditorIds() != null) {
            spec = spec.and((root, query, cb) -> c.allowedCreditorIds().isEmpty() ? cb.disjunction()
                    : root.get("creditorId").in(c.allowedCreditorIds()));
        }
        if (c.creditorId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("creditorId"), c.creditorId()));
        }
        if (c.statuses() != null && !c.statuses().isEmpty()) {
            spec = spec.and((root, query, cb) -> root.get("status").in(c.statuses()));
        }
        if (c.types() != null && !c.types().isEmpty()) {
            spec = spec.and((root, query, cb) -> root.get("type").in(c.types()));
        }
        if (c.origins() != null && !c.origins().isEmpty()) {
            spec = spec.and((root, query, cb) -> root.get("origin").in(c.origins()));
        }
        if (c.assigneeId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("assigneeId"), c.assigneeId()));
        }
        if (c.unassigned()) {
            spec = spec.and((root, query, cb) -> cb.isNull(root.get("assigneeId")));
        }
        if (c.overdue() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("overdue"), c.overdue()));
        }
        if (c.dueUntil() != null) {
            spec = spec.and((root, query, cb) -> cb.or(cb.lessThanOrEqualTo(root.get("internalDueDate"), c.dueUntil()),
                    cb.lessThanOrEqualTo(root.get("responseDueDate"), c.dueUntil())));
        }
        if (c.awaitingCreditor() != null) {
            spec = spec.and((root, query, cb) -> c.awaitingCreditor() ? cb.isNotNull(root.get("awaitingCreditorSince"))
                    : cb.isNull(root.get("awaitingCreditorSince")));
        }
        if (c.noticePending()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("debtorNoticeStatus"), DebtorNoticeStatus.PENDING));
        }
        if (c.adjustmentPending()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("adjustmentStatus"), DisputeAdjustmentStatus.PENDING));
        }
        if (c.supervisionAsOf() != null) {
            spec = spec.and((root, query, cb) -> cb.or(cb.isNull(root.get("assigneeId")),
                    cb.lessThan(root.get("internalDueDate"), c.supervisionAsOf()),
                    cb.lessThan(root.get("responseDueDate"), c.supervisionAsOf())));
        }
        return findAll(spec, pageable);
    }

    @Override
    default Map<UUID, Long> countOpenByAssignees(Collection<UUID> assigneeIds) {
        if (assigneeIds == null || assigneeIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : openCountRows(assigneeIds)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }

    @Query("""
            select d.assigneeId, count(d) from Dispute d
             where d.assigneeId in :assigneeIds
               and d.status in (br.com.gestorcobranca.collection.domain.model.DisputeStatus.OPEN,
                                br.com.gestorcobranca.collection.domain.model.DisputeStatus.UNDER_REVIEW)
             group by d.assigneeId
            """)
    List<Object[]> openCountRows(Collection<UUID> assigneeIds);

    @Override
    @Query("""
            select d from Dispute d
             where d.debtorId = :debtorId
               and d.adjustmentStatus = br.com.gestorcobranca.collection.domain.model.DisputeAdjustmentStatus.PENDING
            """)
    List<Dispute> findPendingAdjustmentsByDebtorId(UUID debtorId);

    @Override
    default List<Dispute> findPendingAdjustments(int limit) {
        return pendingAdjustments(Limit.of(limit));
    }

    @Query("""
            select d from Dispute d
             where d.adjustmentStatus = br.com.gestorcobranca.collection.domain.model.DisputeAdjustmentStatus.PENDING
             order by d.resolvedAt, d.id
            """)
    List<Dispute> pendingAdjustments(Limit limit);
}
