package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.debt.domain.repository.TitleSearchCriteria;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface DebtTitleJpaRepository extends JpaRepository<DebtTitle, UUID>, JpaSpecificationExecutor<DebtTitle>,
        DebtTitleRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from DebtTitle t where t.id = :id")
    Optional<DebtTitle> findByIdForUpdate(UUID id);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from DebtTitle t where t.id in :ids order by t.id")
    List<DebtTitle> findAllByIdInForUpdate(Collection<UUID> ids);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from DebtTitle t where t.creditorId = :creditorId and t.externalId = :externalId")
    Optional<DebtTitle> findByCreditorIdAndExternalIdForUpdate(UUID creditorId, String externalId);

    @Override
    default List<DebtTitle> findByDebtor(UUID debtorId, Collection<UUID> allowedCreditorIds) {
        if (allowedCreditorIds == null) {
            return findByDebtorIdOrderByCreditorIdAscDueDateAsc(debtorId);
        }
        if (allowedCreditorIds.isEmpty()) {
            return List.of();
        }
        return findByDebtorIdAndCreditorIdInOrderByCreditorIdAscDueDateAsc(debtorId, allowedCreditorIds);
    }

    List<DebtTitle> findByDebtorIdOrderByCreditorIdAscDueDateAsc(UUID debtorId);

    List<DebtTitle> findByDebtorIdAndCreditorIdInOrderByCreditorIdAscDueDateAsc(UUID debtorId, Collection<UUID> creditorIds);

    @Override
    boolean existsByDebtorIdAndCreditorId(UUID debtorId, UUID creditorId);

    @Override
    boolean existsByDebtorIdAndCreditorIdNot(UUID debtorId, UUID creditorId);

    @Override
    @Query("""
            select case when count(t) > 0 then true else false end from DebtTitle t where t.debtorId = :debtorId
            and t.status in (br.com.gestorcobranca.debt.domain.model.TitleStatus.OPEN,
                             br.com.gestorcobranca.debt.domain.model.TitleStatus.SUSPENDED)
            """)
    boolean existsOpenByDebtorId(UUID debtorId);

    @Override
    @Query("select distinct t.creditorId from DebtTitle t where t.debtorId = :debtorId")
    List<UUID> findCreditorIdsByDebtorId(UUID debtorId);

    @Override
    default List<UUID> findDebtorIdsByImportBatch(UUID creditorId, UUID importBatchId, UUID afterDebtorId, int limit) {
        return debtorIdsByImportBatch(creditorId, importBatchId, afterDebtorId == null ? new UUID(0L, 0L) : afterDebtorId,
                Limit.of(limit));
    }

    @Query("""
            select distinct t.debtorId from DebtTitle t
             where t.creditorId = :creditorId and t.importBatchId = :importBatchId and t.debtorId > :afterDebtorId
             order by t.debtorId
            """)
    List<UUID> debtorIdsByImportBatch(UUID creditorId, UUID importBatchId, UUID afterDebtorId, Limit limit);

    @Override
    default Page<DebtTitle> search(TitleSearchCriteria c, Pageable pageable) {
        Specification<DebtTitle> spec = Specification.unrestricted();
        if (c.allowedCreditorIds() != null) {
            spec = spec.and((root, query, cb) -> c.allowedCreditorIds().isEmpty()
                    ? cb.disjunction() : root.get("creditorId").in(c.allowedCreditorIds()));
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
        if (c.statuses() != null) {
            spec = spec.and((root, query, cb) -> c.statuses().isEmpty()
                    ? cb.disjunction() : root.get("status").in(c.statuses()));
        }
        if (c.dueOnOrAfter() != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("dueDate"), c.dueOnOrAfter()));
        }
        if (c.dueOnOrBefore() != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("dueDate"), c.dueOnOrBefore()));
        }
        return findAll(spec, pageable);
    }
}
