package br.com.gestorcobranca.publicapi.infrastructure.persistence;

import br.com.gestorcobranca.publicapi.domain.model.PricePlan;
import br.com.gestorcobranca.publicapi.domain.repository.PricePlanRepository;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface PricePlanJpaRepository extends JpaRepository<PricePlan, UUID>, PricePlanRepository {

    @Override
    @Query("select p from PricePlan p order by p.code asc, p.planVersion desc")
    List<PricePlan> findAllOrderByCodeAscPlanVersionDesc();

    @Override
    @Query("select coalesce(max(p.planVersion), 0) from PricePlan p where p.code = :code")
    int maxVersion(@Param("code") String code);

    @Query("""
            select p from PricePlan p
             where p.status = br.com.gestorcobranca.publicapi.domain.model.PricePlanStatus.PUBLISHED
               and p.effectiveFrom <= :month
               and ((:creditorId is null and p.creditorId is null) or p.creditorId = :creditorId)
             order by p.effectiveFrom desc, p.planVersion desc
            """)
    List<PricePlan> findPublished(@Param("creditorId") UUID creditorId, @Param("month") LocalDate month, Limit limit);

    @Query("""
            select p from PricePlan p
             where p.status = br.com.gestorcobranca.publicapi.domain.model.PricePlanStatus.PUBLISHED
               and p.effectiveFrom <= :month and p.creditorId is null
             order by p.effectiveFrom desc, p.planVersion desc
            """)
    List<PricePlan> findPublishedDefault(@Param("month") LocalDate month, Limit limit);

    @Override
    default Optional<PricePlan> findApplicable(UUID creditorId, LocalDate monthStart) {
        List<PricePlan> own = findPublished(creditorId, monthStart, Limit.of(1));
        if (!own.isEmpty()) {
            return Optional.of(own.getFirst());
        }
        return findPublishedDefault(monthStart, Limit.of(1)).stream().findFirst();
    }
}
