package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.TitleAmountDivergence;
import br.com.gestorcobranca.debt.domain.repository.TitleAmountDivergenceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface TitleAmountDivergenceJpaRepository extends Repository<TitleAmountDivergence, UUID>,
        TitleAmountDivergenceRepository {

    @Override
    TitleAmountDivergence save(TitleAmountDivergence divergence);

    @Override
    Optional<TitleAmountDivergence> findById(UUID id);

    @Override
    @Query("""
            select d from TitleAmountDivergence d
            where d.titleId = :titleId and d.status = br.com.gestorcobranca.debt.domain.model.AmountDivergenceStatus.OPEN
            """)
    Optional<TitleAmountDivergence> findOpenByTitleId(@Param("titleId") UUID titleId);

    @Override
    List<TitleAmountDivergence> findByTitleIdOrderByDetectedAtDesc(UUID titleId);

    @Override
    @Query("""
            select d from TitleAmountDivergence d
            where d.status = br.com.gestorcobranca.debt.domain.model.AmountDivergenceStatus.OPEN
              and (:allCreditors = true or d.creditorId in :creditorIds)
            order by d.detectedAt
            """)
    Page<TitleAmountDivergence> findOpen(@Param("creditorIds") Collection<UUID> creditorIds,
                                         @Param("allCreditors") boolean allCreditors, Pageable pageable);
}
