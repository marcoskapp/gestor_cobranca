package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.shared.paging.ChangeQuery;
import br.com.gestorcobranca.shared.paging.ChangeSpecifications;

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

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

interface CollectionCaseJpaRepository extends JpaRepository<CollectionCase, UUID>,
        JpaSpecificationExecutor<CollectionCase>, CollectionCaseRepository {

    UUID MIN_ID = new UUID(0L, 0L);

    @Override
    List<CollectionCase> findAllByIdIn(Collection<UUID> ids);

    @Override
    @Query("select c.debtorId from CollectionCase c where c.id = :id")
    Optional<UUID> findDebtorIdById(UUID id);

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
    @Query("""
            select c from CollectionCase c
             where c.debtorId = :debtorId and c.portfolioId = :portfolioId
               and c.status <> br.com.gestorcobranca.collection.domain.model.CaseStatus.CLOSED
            """)
    Optional<CollectionCase> findActiveByDebtorAndPortfolio(UUID debtorId, UUID portfolioId);

    @Override
    @Query("""
            select c from CollectionCase c
             where c.debtorId = :debtorId and c.creditorId = :creditorId
               and c.status <> br.com.gestorcobranca.collection.domain.model.CaseStatus.CLOSED
             order by c.id
            """)
    List<CollectionCase> findActiveByDebtorAndCreditor(UUID debtorId, UUID creditorId);

    @Override
    default Map<UUID, Long> countActiveAssignedByUser(UUID queueId) {
        Map<UUID, Long> result = new HashMap<>();
        for (Object[] row : activeAssignedByUser(queueId)) {
            result.put((UUID) row[0], (Long) row[1]);
        }
        return result;
    }

    @Query("""
            select c.assigneeUserId, count(c) from CollectionCase c
             where c.queueId = :queueId and c.assigneeUserId is not null
               and c.status <> br.com.gestorcobranca.collection.domain.model.CaseStatus.CLOSED
             group by c.assigneeUserId
            """)
    List<Object[]> activeAssignedByUser(UUID queueId);

    @Override
    @Query("""
            select count(c) from CollectionCase c
             where c.queueId = :queueId and c.assigneeUserId is null
               and c.status <> br.com.gestorcobranca.collection.domain.model.CaseStatus.CLOSED
            """)
    long countWaiting(UUID queueId);

    @Override
    @Query("""
            select count(c) from CollectionCase c
             where c.queueId = :queueId and c.assigneeUserId is null and c.eligible
               and c.ownerType = br.com.gestorcobranca.collection.domain.model.OwnerType.QUEUE
               and c.status in (br.com.gestorcobranca.collection.domain.model.CaseStatus.OPEN,
                                br.com.gestorcobranca.collection.domain.model.CaseStatus.IN_NEGOTIATION,
                                br.com.gestorcobranca.collection.domain.model.CaseStatus.PROMISE)
               and (c.nextActionAt is null or c.nextActionAt <= :now)
            """)
    long countAvailable(UUID queueId, Instant now);

    @Override
    @Query("""
            select count(c) from CollectionCase c
             where c.assigneeUserId = :userId
               and c.status <> br.com.gestorcobranca.collection.domain.model.CaseStatus.CLOSED
            """)
    long countActiveByAssignee(UUID userId);

    @Override
    @Query("""
            select count(c) from CollectionCase c
             where c.queueId = :queueId and c.assigneeUserId = :userId
               and c.status <> br.com.gestorcobranca.collection.domain.model.CaseStatus.CLOSED
            """)
    long countActiveAssigned(UUID queueId, UUID userId);

    @Override
    default List<UUID> findActiveIdsByDebtor(UUID debtorId, UUID creditorId) {
        return creditorId == null ? activeIdsByDebtor(debtorId) : activeIdsByDebtorAndCreditor(debtorId, creditorId);
    }

    @Query("""
            select c.id from CollectionCase c
             where c.debtorId = :debtorId
               and c.status <> br.com.gestorcobranca.collection.domain.model.CaseStatus.CLOSED
             order by c.id
            """)
    List<UUID> activeIdsByDebtor(UUID debtorId);

    @Query("""
            select c.id from CollectionCase c
             where c.debtorId = :debtorId and c.creditorId = :creditorId
               and c.status <> br.com.gestorcobranca.collection.domain.model.CaseStatus.CLOSED
             order by c.id
            """)
    List<UUID> activeIdsByDebtorAndCreditor(UUID debtorId, UUID creditorId);

    @Override
    default List<CollectionCase> findChanges(UUID creditorId, UUID portfolioId, ChangeQuery query) {
        Specification<CollectionCase> spec = ChangeSpecifications.<CollectionCase>window(query, List.of(creditorId));
        if (portfolioId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("portfolioId"), portfolioId));
        }
        return findAll(spec, ChangeSpecifications.page(query)).getContent();
    }

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
