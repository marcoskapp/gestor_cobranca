package br.com.gestorcobranca.complaint.infrastructure.persistence;

import br.com.gestorcobranca.complaint.domain.model.Complaint;
import br.com.gestorcobranca.complaint.domain.model.ComplaintStatus;
import br.com.gestorcobranca.complaint.domain.repository.ComplaintRepository;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Índices: {@code ix_complaint_open_due}, {@code ix_complaint_creditor_received}, {@code ix_complaint_debtor}. */
interface ComplaintJpaRepository extends JpaRepository<Complaint, UUID>, JpaSpecificationExecutor<Complaint>,
        ComplaintRepository {

    Set<ComplaintStatus> AWAITING = EnumSet.of(ComplaintStatus.RECEIVED, ComplaintStatus.IN_PROGRESS);

    @Override
    default Page<Complaint> search(Criteria c, Pageable pageable) {
        Specification<Complaint> spec = Specification.unrestricted();
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
        if (c.channels() != null && !c.channels().isEmpty()) {
            spec = spec.and((root, query, cb) -> root.get("channel").in(c.channels()));
        }
        if (c.debtorId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("debtorId"), c.debtorId()));
        }
        if (c.caseId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("caseId"), c.caseId()));
        }
        if (c.assigneeId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("assigneeId"), c.assigneeId()));
        }
        if (c.unassigned()) {
            spec = spec.and((root, query, cb) -> cb.isNull(root.get("assigneeId")));
        }
        if (c.externalProtocol() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("externalProtocol"), c.externalProtocol()));
        }
        if (c.overdue() != null && c.today() != null) {
            spec = spec.and((root, query, cb) -> c.overdue()
                    ? cb.and(root.get("status").in(AWAITING), cb.lessThan(root.get("dueDate"), c.today()))
                    : cb.or(root.get("status").in(AWAITING).not(),
                    cb.greaterThanOrEqualTo(root.get("dueDate"), c.today())));
        }
        if (c.dueUntil() != null) {
            spec = spec.and((root, query, cb) -> cb.and(root.get("status").in(AWAITING),
                    cb.lessThanOrEqualTo(root.get("warningDate"), c.dueUntil())));
        }
        return findAll(spec, pageable);
    }

    @Override
    default List<Complaint> findOverdueUnnotified(LocalDate today, int limit) {
        return findOverdue(AWAITING, today, Limit.of(limit));
    }

    @Query("""
            select c from Complaint c
             where c.status in :statuses and c.dueDate < :today and c.overdueNotifiedAt is null
             order by c.dueDate, c.id
            """)
    List<Complaint> findOverdue(Set<ComplaintStatus> statuses, LocalDate today, Limit limit);
}
