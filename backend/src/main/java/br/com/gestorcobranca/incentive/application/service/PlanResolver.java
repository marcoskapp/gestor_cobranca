package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries.WorkforceMember;
import br.com.gestorcobranca.incentive.domain.model.plan.CommissionPlan;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules;
import br.com.gestorcobranca.incentive.domain.repository.CommissionPlanRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolve a versão de plano vigente numa data: entre as versões publicadas (ou aposentadas com
 * vigência que alcança a data), a de maior prioridade cuja elegibilidade cobre o negociador.
 */
@Component
class PlanResolver {

    private final CommissionPlanRepository plans;
    private final PlanRulesCodec codec;

    PlanResolver(CommissionPlanRepository plans, PlanRulesCodec codec) {
        this.plans = plans;
        this.codec = codec;
    }

    record ResolvedPlan(CommissionPlan plan, PlanRules rules) {
        UUID id() {
            return plan.getId();
        }
    }

    /** Versões vigentes na data, da maior para a menor prioridade. */
    List<ResolvedPlan> effectiveOn(LocalDate date) {
        return plans.findEffectiveCandidates().stream().filter(p -> p.isEffectiveOn(date))
                .sorted(Comparator.comparingInt(CommissionPlan::getPriority).reversed()
                        .thenComparing(CommissionPlan::getCode))
                .map(p -> new ResolvedPlan(p, codec.decode(p))).toList();
    }

    Optional<ResolvedPlan> forMember(WorkforceMember member, LocalDate date) {
        if (member == null) {
            return Optional.empty();
        }
        return covering(effectiveOn(date), member);
    }

    /** Entre versões já ordenadas por prioridade ({@link #effectiveOn}), a primeira que cobre o negociador. */
    static Optional<ResolvedPlan> covering(List<ResolvedPlan> effective, WorkforceMember member) {
        return effective.stream()
                .filter(p -> p.rules().eligibility().coversMember(member.roleCodes(), member.teamIds())).findFirst();
    }

    /** Plano de maior prioridade vigente (parâmetros gerais: janela de influência, carência do pool). */
    Optional<ResolvedPlan> primary(LocalDate date) {
        return effectiveOn(date).stream().findFirst();
    }

    Optional<ResolvedPlan> byId(UUID planId) {
        return planId == null ? Optional.empty()
                : plans.findById(planId).map(p -> new ResolvedPlan(p, codec.decode(p)));
    }

    /** Maior carência entre os planos vigentes na data (fechamento só depois de todas liberadas). */
    int maxGraceBusinessDays(LocalDate date, int fallback) {
        return effectiveOn(date).stream().mapToInt(p -> p.rules().clearing().graceBusinessDays()).max().orElse(fallback);
    }
}
