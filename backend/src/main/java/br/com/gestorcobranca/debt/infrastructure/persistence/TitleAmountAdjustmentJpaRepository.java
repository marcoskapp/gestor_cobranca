package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.AmountAdjustmentStatus;
import br.com.gestorcobranca.debt.domain.model.TitleAmountAdjustment;
import br.com.gestorcobranca.debt.domain.repository.TitleAmountAdjustmentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Sem remoção: o histórico de correções é append-only (trigger no banco). */
interface TitleAmountAdjustmentJpaRepository extends Repository<TitleAmountAdjustment, UUID>,
        TitleAmountAdjustmentRepository {

    @Override
    TitleAmountAdjustment save(TitleAmountAdjustment adjustment);

    @Override
    Optional<TitleAmountAdjustment> findById(UUID id);

    @Override
    List<TitleAmountAdjustment> findByTitleIdOrderByRequestedAtDesc(UUID titleId);

    @Override
    boolean existsByTitleIdAndStatus(UUID titleId, AmountAdjustmentStatus status);

    @Override
    @Query(value = """
            select coalesce(sum(l.previous_balance - l.new_balance), 0)
              from debt.title_amount_adjustment a
              join debt.title_amount_adjustment_line l on l.adjustment_id = a.id
             where a.title_id = :titleId
               and a.requested_at >= :since
               and a.approval_mode = 'WITHIN_LIMIT'
               and a.status = 'APPLIED'
               and l.new_balance < l.previous_balance
            """, nativeQuery = true)
    BigDecimal sumWithinLimitReductionsSince(@Param("titleId") UUID titleId, @Param("since") Instant since);

    @Override
    @Query("""
            select a from TitleAmountAdjustment a
            where a.status = br.com.gestorcobranca.debt.domain.model.AmountAdjustmentStatus.PENDING_APPROVAL
              and (:allCreditors = true or a.creditorId in :creditorIds)
              and (:excludeRequester is null or a.requestedBy <> :excludeRequester)
            order by a.requestedAt
            """)
    Page<TitleAmountAdjustment> findPending(@Param("creditorIds") Collection<UUID> creditorIds,
                                            @Param("allCreditors") boolean allCreditors,
                                            @Param("excludeRequester") UUID excludeRequester, Pageable pageable);
}
