package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.domain.model.plan.CommissionPlan;
import br.com.gestorcobranca.incentive.domain.model.plan.GoalTarget;
import br.com.gestorcobranca.incentive.domain.repository.CommissionPlanRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaCommissionPlanRepository implements CommissionPlanRepository {

    private final CommissionPlanJpa plans;
    private final GoalTargetJpa goals;

    JpaCommissionPlanRepository(CommissionPlanJpa plans, GoalTargetJpa goals) {
        this.plans = plans;
        this.goals = goals;
    }

    @Override
    public CommissionPlan save(CommissionPlan plan) {
        return plans.save(plan);
    }

    @Override
    public Optional<CommissionPlan> findById(UUID id) {
        return plans.findById(id);
    }

    @Override
    public List<CommissionPlan> findAllOrdered() {
        return plans.findAllOrdered();
    }

    @Override
    public List<CommissionPlan> findByCodeOrderByPlanVersionDesc(String code) {
        return plans.findByCodeOrderByPlanVersionDesc(code);
    }

    @Override
    public List<CommissionPlan> findEffectiveCandidates() {
        return plans.findEffectiveCandidates();
    }

    @Override
    public boolean existsByCode(String code) {
        return plans.existsByCode(code);
    }

    @Override
    public GoalTarget saveGoal(GoalTarget goal) {
        return goals.save(goal);
    }

    @Override
    public Optional<GoalTarget> findGoal(LocalDate periodStart, GoalTarget.SubjectType type, UUID subjectId) {
        return goals.findByPeriodStartAndSubjectTypeAndSubjectId(periodStart, type, subjectId);
    }

    @Override
    public List<GoalTarget> findGoals(LocalDate periodStart) {
        return goals.findByPeriodStart(periodStart);
    }
}
