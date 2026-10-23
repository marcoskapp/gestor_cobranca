package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.domain.model.plan.CommissionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.UUID;

interface CommissionPlanJpa extends JpaRepository<CommissionPlan, UUID> {

    @Query("select p from CommissionPlan p order by p.code, p.planVersion desc")
    List<CommissionPlan> findAllOrdered();

    List<CommissionPlan> findByCodeOrderByPlanVersionDesc(String code);

    @Query("""
            select p from CommissionPlan p
            where p.status <> br.com.gestorcobranca.incentive.domain.model.plan.PlanStatus.DRAFT
            order by p.priority desc, p.code, p.planVersion desc
            """)
    List<CommissionPlan> findEffectiveCandidates();

    boolean existsByCode(String code);
}
