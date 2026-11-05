package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.AmountAdjustmentStatus;
import br.com.gestorcobranca.debt.domain.model.TitleAmountAdjustment;
import br.com.gestorcobranca.debt.domain.repository.TitleAmountAdjustmentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

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
