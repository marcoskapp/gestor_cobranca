package br.com.gestorcobranca.incentive.domain.repository;

import br.com.gestorcobranca.incentive.domain.model.plan.CommissionPlan;
import br.com.gestorcobranca.incentive.domain.model.plan.GoalTarget;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Versões de planos e metas por período. */
public interface CommissionPlanRepository {

    CommissionPlan save(CommissionPlan plan);

    Optional<CommissionPlan> findById(UUID id);

    List<CommissionPlan> findAllOrdered();

    List<CommissionPlan> findByCodeOrderByPlanVersionDesc(String code);

    /** Versões publicadas ou aposentadas (as que podem ter vigência). */
    List<CommissionPlan> findEffectiveCandidates();

    boolean existsByCode(String code);

    GoalTarget saveGoal(GoalTarget goal);

    Optional<GoalTarget> findGoal(LocalDate periodStart, GoalTarget.SubjectType type, UUID subjectId);

    List<GoalTarget> findGoals(LocalDate periodStart);
}
