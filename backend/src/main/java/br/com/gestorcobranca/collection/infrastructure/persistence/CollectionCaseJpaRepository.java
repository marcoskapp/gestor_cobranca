package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.repository.CaseSearchCriteria;
import br.com.gestorcobranca.collection.domain.repository.CollectionCaseRepository;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface CollectionCaseJpaRepository extends JpaRepository<CollectionCase, UUID>,
        JpaSpecificationExecutor<CollectionCase>, CollectionCaseRepository {

    UUID MIN_ID = new UUID(0L, 0L);

    @Override
    List<CollectionCase> findAllByIdIn(Collection<UUID> ids);

    @Override
    @Query("""
            select c from CollectionCase c
             where c.debtorId = :debtorId
               and c.status <> br.com.gestorcobranca.collection.domain.model.CaseStatus.CLOSED
            """)
    List<CollectionCase> findActiveByDebtorId(UUID debtorId);

    @Override
    List<CollectionCase> findByDebtorIdOrderByOpenedAtDesc(UUID debtorId);

    @Override
    default List<UUID> findActiveIdsAfter(UUID afterId, int limit) {
        return activeIdsAfter(afterId == null ? MIN_ID : afterId, Limit.of(limit));
    }

    @Query("""
            select c.id from CollectionCase c
             where c.status <> br.com.gestorcobranca.collection.domain.model.CaseStatus.CLOSED and c.id > :afterId
             order by c.id
            """)
    List<UUID> activeIdsAfter(UUID afterId, Limit limit);

    @Override
    default List<UUID> findActiveIdsByPortfolioAfter(UUID portfolioId, UUID afterId, int limit) {
        return activeIdsByPortfolioAfter(portfolioId, afterId == null ? MIN_ID : afterId, Limit.of(limit));
    }

    @Query("""
            select c.id from CollectionCase c
             where c.portfolioId = :portfolioId
               and c.status <> br.com.gestorcobranca.collection.domain.model.CaseStatus.CLOSED and c.id > :afterId
             order by c.id
            """)
    List<UUID> activeIdsByPortfolioAfter(UUID portfolioId, UUID afterId, Limit limit);

    @Override
    default List<UUID> findActiveIdsByCreditorAfter(UUID creditorId, UUID afterId, int limit) {
        return activeIdsByCreditorAfter(creditorId, afterId == null ? MIN_ID : afterId, Limit.of(limit));
    }

    @Query("""
            select c.id from CollectionCase c
             where c.creditorId = :creditorId
               and c.status <> br.com.gestorcobranca.collection.domain.model.CaseStatus.CLOSED and c.id > :afterId
             order by c.id
            """)
    List<UUID> activeIdsByCreditorAfter(UUID creditorId, UUID afterId, Limit limit);

    @Override
    default List<CollectionCase> findActiveByAssignee(UUID userId, int limit) {
        return activeByAssignee(userId, Limit.of(limit));
    }

    @Query("""
            select c from CollectionCase c
             where c.assigneeUserId = :userId
               and c.status <> br.com.gestorcobranca.collection.domain.model.CaseStatus.CLOSED
             order by c.id
            """)
    List<CollectionCase> activeByAssignee(UUID userId, Limit limit);

    @Override
    default Page<CollectionCase> search(CaseSearchCriteria c, Pageable pageable) {
        Specification<CollectionCase> spec = Specification.unrestricted();
        if (c.allowedCreditorIds() != null) {
            spec = spec.and((root, query, cb) -> c.allowedCreditorIds().isEmpty() ? cb.disjunction()
                    : root.get("creditorId").in(c.allowedCreditorIds()));
        }
        if (c.creditorId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("creditorId"), c.creditorId()));
        }
        if (c.portfolioId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("portfolioId"), c.portfolioId()));
        }
        if (c.debtorId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("debtorId"), c.debtorId()));
        }
        if (c.statuses() != null && !c.statuses().isEmpty()) {
            spec = spec.and((root, query, cb) -> root.get("status").in(c.statuses()));
        }
        if (c.queueId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("queueId"), c.queueId()));
        }
        if (c.assigneeUserId() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("assigneeUserId"), c.assigneeUserId()));
        }
        if (c.ownerType() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("ownerType"), c.ownerType()));
        }
        if (c.eligible() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("eligible"), c.eligible()));
        }
        return findAll(spec, pageable);
    }
}
