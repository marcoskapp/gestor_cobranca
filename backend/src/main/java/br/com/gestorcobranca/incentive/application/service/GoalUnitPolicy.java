package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries.TeamStructure;
import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries.WorkforceMember;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases.GoalUnitBasis;
import br.com.gestorcobranca.incentive.application.service.PlanResolver.ResolvedPlan;
import br.com.gestorcobranca.incentive.domain.model.plan.GoalMetric;
import br.com.gestorcobranca.incentive.domain.model.plan.GoalScope;
import br.com.gestorcobranca.incentive.domain.model.plan.GoalTarget;
import br.com.gestorcobranca.incentive.domain.model.plan.GoalUnit;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Unidades aceitas para a meta de um negociador ou equipe num período, pela métrica da meta do
 * plano que vai usá-la (mesma resolução do fechamento, {@link StatementCalculator}):
 *
 * <ul>
 *   <li>negociador: plano vigente de maior prioridade que o cobre, se a meta do plano for individual;</li>
 *   <li>equipe: planos vigentes dos membros e do supervisor cuja meta é de equipe.</li>
 * </ul>
 *
 * Quando nenhum plano vigente usa a meta (sem plano, plano sem meta, meta de outro escopo ou
 * período futuro ainda sem versão publicada) as duas unidades são aceitas, e o fechamento
 * desconsidera a meta se a unidade não coincidir com a métrica (nunca reinterpreta o número).
 */
@Component
class GoalUnitPolicy {

    private final PlanResolver planResolver;
    private final Workforce workforce;

    GoalUnitPolicy(PlanResolver planResolver, Workforce workforce) {
        this.planResolver = planResolver;
        this.workforce = workforce;
    }

    /**
     * @param allowed unidades aceitas
     * @param basis   de onde veio a restrição
     * @param metric  métrica da meta do plano (quando única)
     * @param plan    plano que determina a unidade (quando único)
     */
    record Resolution(Set<GoalUnit> allowed, GoalUnitBasis basis, GoalMetric metric, ResolvedPlan plan) {
    }

    Resolution resolve(LocalDate periodStart, GoalTarget.SubjectType type, UUID subjectId) {
        Guard.require(subjectId != null, "goal.subject_required", "Informe o negociador ou a equipe");
        List<ResolvedPlan> effective = planResolver.effectiveOn(periodStart);
        if (type == GoalTarget.SubjectType.USER) {
            WorkforceMember member = workforce.member(subjectId)
                    .orElseThrow(() -> new BusinessRuleException(
                            "goal.subject_not_found", "Negociador inexistente"));
            return forPlans(PlanResolver.covering(effective, member).stream().toList(), GoalScope.INDIVIDUAL);
        }
        TeamStructure team = workforce.teams().stream().filter(t -> t.id().equals(subjectId)).findFirst()
                .orElseThrow(() -> new BusinessRuleException(
                        "goal.subject_not_found", "Equipe inexistente ou inativa"));
        Set<UUID> people = new HashSet<>(team.memberIds());
        if (team.supervisorId() != null) {
            people.add(team.supervisorId());
        }
        Map<UUID, WorkforceMember> members = workforce.members(people);
        List<ResolvedPlan> plans = members.values().stream()
                .map(m -> PlanResolver.covering(effective, m)).flatMap(Optional::stream)
                .distinct().toList();
        return forPlans(plans, GoalScope.TEAM);
    }

    private static Resolution forPlans(List<ResolvedPlan> plans, GoalScope scope) {
        if (plans.isEmpty()) {
            return new Resolution(EnumSet.allOf(GoalUnit.class), GoalUnitBasis.NO_PLAN, null, null);
        }
        List<ResolvedPlan> using = plans.stream()
                .filter(p -> p.rules().goal() != null && p.rules().goal().scope() == scope).toList();
        if (using.isEmpty()) {
            return new Resolution(EnumSet.allOf(GoalUnit.class), GoalUnitBasis.PLAN_WITHOUT_GOAL, null,
                    plans.size() == 1 ? plans.getFirst() : null);
        }
        Set<GoalUnit> units = EnumSet.noneOf(GoalUnit.class);
        Set<GoalMetric> metrics = EnumSet.noneOf(GoalMetric.class);
        using.forEach(p -> {
            metrics.add(p.rules().goal().metric());
            units.add(p.rules().goal().metric().unit());
        });
        if (units.size() > 1) {
            return new Resolution(units, GoalUnitBasis.MIXED, null, null);
        }
        return new Resolution(units, GoalUnitBasis.PLAN, metrics.size() == 1 ? metrics.iterator().next() : null,
                using.size() == 1 ? using.getFirst() : null);
    }
}
