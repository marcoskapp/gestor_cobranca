package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DisputeStatus;
import br.com.gestorcobranca.collection.domain.repository.DisputeRepository;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

interface DisputeJpaRepository extends JpaRepository<Dispute, UUID>, JpaSpecificationExecutor<Dispute>,
        DisputeRepository {

    @Override
    @Query("""
            select count(d) > 0 from Dispute d
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
    default Page<Dispute> search(Set<UUID> allowedCreditorIds, UUID creditorId, Set<DisputeStatus> statuses,
                                 Boolean overdue, Pageable pageable) {
        Specification<Dispute> spec = Specification.unrestricted();
        if (allowedCreditorIds != null) {
            spec = spec.and((root, query, cb) -> allowedCreditorIds.isEmpty() ? cb.disjunction()
                    : root.get("creditorId").in(allowedCreditorIds));
        }
        if (creditorId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("creditorId"), creditorId));
        }
        if (statuses != null && !statuses.isEmpty()) {
            spec = spec.and((root, query, cb) -> root.get("status").in(statuses));
        }
        if (overdue != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("overdue"), overdue));
        }
        return findAll(spec, pageable);
    }
}
