package br.com.gestorcobranca.legal.infrastructure.persistence;

import br.com.gestorcobranca.legal.domain.model.DeadlineStatus;
import br.com.gestorcobranca.legal.domain.model.LegalDeadline;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.repository.DeadlineSearchCriteria;
import br.com.gestorcobranca.legal.domain.repository.LegalDeadlineRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface LegalDeadlineJpaRepository extends JpaRepository<LegalDeadline, UUID>, JpaSpecificationExecutor<LegalDeadline>,
        LegalDeadlineRepository {

    @Override
    Optional<LegalDeadline> findById(UUID id);

    @Override
    List<LegalDeadline> findByMatterIdOrderByDueDateAscCreatedAtAsc(UUID matterId);

    @Override
    List<LegalDeadline> findByMatterIdAndStatus(UUID matterId, DeadlineStatus status);

    @Override
    List<LegalDeadline> findByStatusAndIdGreaterThanOrderByIdAsc(DeadlineStatus status, UUID afterId, Pageable page);

    @Override
    default Page<LegalDeadline> search(DeadlineSearchCriteria c, Pageable pageable) {
        Specification<LegalDeadline> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (c.creditorScope() != null) {
                p.add(c.creditorScope().isEmpty() ? cb.disjunction() : root.get("creditorId").in(c.creditorScope()));
            }
            if (c.lawFirmScope() != null) {
                Subquery<UUID> firmMatters = query.subquery(UUID.class);
                Root<LegalMatter> m = firmMatters.from(LegalMatter.class);
                firmMatters.select(m.get("id")).where(cb.equal(m.get("lawFirmId"), c.lawFirmScope()));
                p.add(root.get("matterId").in(firmMatters));
            }
            if (c.matterId() != null) {
                p.add(cb.equal(root.get("matterId"), c.matterId()));
            }
            if (c.statuses() != null && !c.statuses().isEmpty()) {
                p.add(root.get("status").in(c.statuses()));
            }
            if (c.dueFrom() != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("dueDate"), c.dueFrom()));
            }
            if (c.dueTo() != null) {
                p.add(cb.lessThanOrEqualTo(root.get("dueDate"), c.dueTo()));
            }
            if (c.responsibleUserId() != null) {
                p.add(cb.equal(root.get("responsibleUserId"), c.responsibleUserId()));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return findAll(spec, pageable);
    }
}
