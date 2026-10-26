package br.com.gestorcobranca.incentive;

import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases.GoalCommand;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases.GoalUnitBasis;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases.GoalUnitOptions;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases.GoalView;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases.PlanCommand;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases.PlanView;
import br.com.gestorcobranca.incentive.application.port.in.StatementUseCases;
import br.com.gestorcobranca.incentive.application.port.in.StatementUseCases.StatementLineView;
import br.com.gestorcobranca.incentive.domain.model.plan.GoalMetric;
import br.com.gestorcobranca.incentive.domain.model.plan.GoalScope;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.GoalComponent;
import br.com.gestorcobranca.incentive.domain.model.plan.StandardPlans;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unidade explícita da meta do período (decisões 387 e 500–504): o servidor aceita só a unidade da
 * métrica da meta do plano que vai usá-la, valida casas e limites, audita a unidade e, no cálculo,
 * desconsidera (sem reinterpretar) a meta gravada em unidade diferente da métrica.
 */
class GoalUnitIT extends IncentiveTestSupport {

    private final YearMonth current = YearMonth.now(ZONE);
    private final YearMonth next = current.plusMonths(1);

    @Autowired
    CommissionPlanUseCases plans;
    @Autowired
    StatementUseCases statements;

    @BeforeEach
    void setUp() {
        provisionTenant("goalunit");
    }

    @Test
    void negotiatorUnderTheStandardPlanAcceptsOnlyCurrency() {
        GoalUnitOptions options = as(manager, DefaultRoles.GESTOR, () -> plans.goalUnitOptions(current, "USER", operatorX));
        assertThat(options.units()).containsExactly("CURRENCY");
        assertThat(options.basis()).isEqualTo(GoalUnitBasis.PLAN);
        assertThat(options.metric()).isEqualTo("RECOVERED");
        assertThat(options.planCode()).isEqualTo(StandardPlans.PADRAO_MERCADO);

        assertThatThrownBy(() -> setGoal("USER", operatorX, "12.5", "PERCENT"))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "goal.unit_not_allowed")
                .hasMessageContaining("R$");
        assertThatThrownBy(() -> setGoal("USER", operatorX, "80000", null))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "goal.unit_required");
        assertThatThrownBy(() -> setGoal("USER", operatorX, "80000.005", "CURRENCY"))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "goal.target_scale_invalid");

        GoalView saved = setGoal("USER", operatorX, "80000.50", "CURRENCY");
        assertThat(saved.unit()).isEqualTo("CURRENCY");
        assertThat(saved.target()).isEqualByComparingTo("80000.50");
        assertThat(as(manager, DefaultRoles.GESTOR, () -> plans.goals(current))).extracting(GoalView::unit)
                .containsExactly("CURRENCY");
        assertThat(count("select count(*) from shared.audit_event where action = 'commission_goal.defined' "
                + "and entity_id = ? and after_state ->> 'unit' = 'CURRENCY'", saved.id())).isEqualTo(1);
    }

    @Test
    void teamGoalUnitFollowsThePlanThatUsesTeamGoals() {
        // o plano padrão só tem meta individual: a meta da equipe não tem métrica definida no mês corrente
        GoalUnitOptions now = as(manager, DefaultRoles.GESTOR, () -> plans.goalUnitOptions(current, "TEAM", team));
        assertThat(now.units()).containsExactlyInAnyOrder("CURRENCY", "PERCENT");
        assertThat(now.basis()).isEqualTo(GoalUnitBasis.PLAN_WITHOUT_GOAL);

        PlanRules standard = StandardPlans.padraoMercado();
        PlanRules teamRate = new PlanRules(standard.eligibility(), standard.recoveryRate(), standard.fixedPerAgreement(),
                new GoalComponent(GoalMetric.RECOVERY_RATE, GoalScope.TEAM, null, new BigDecimal("80"),
                        new BigDecimal("0.5"), new BigDecimal("110"), new BigDecimal("1.2"),
                        standard.goal().acceleratorMode(), new BigDecimal("300")),
                standard.difficultyMultipliers(), standard.quality(), standard.attribution(), standard.teamPool(),
                standard.limits(), standard.clearing());
        PlanView draft = as(manager, DefaultRoles.GESTOR, () -> plans.createDraft(new PlanCommand("META_TAXA_EQUIPE",
                "Meta de taxa da equipe", null, 10, teamRate)));
        as(manager, DefaultRoles.GESTOR, () -> plans.publish(draft.id(), next.atDay(1)));

        GoalUnitOptions teamOptions = as(manager, DefaultRoles.GESTOR, () -> plans.goalUnitOptions(next, "TEAM", team));
        assertThat(teamOptions.units()).containsExactly("PERCENT");
        assertThat(teamOptions.basis()).isEqualTo(GoalUnitBasis.PLAN);
        assertThat(teamOptions.metric()).isEqualTo("RECOVERY_RATE");
        assertThat(teamOptions.planCode()).isEqualTo("META_TAXA_EQUIPE");
        // o novo plano (prioridade maior) cobre o negociador com meta de equipe: a individual não é usada
        GoalUnitOptions user = as(manager, DefaultRoles.GESTOR, () -> plans.goalUnitOptions(next, "USER", operatorX));
        assertThat(user.basis()).isEqualTo(GoalUnitBasis.PLAN_WITHOUT_GOAL);
        assertThat(user.units()).containsExactlyInAnyOrder("CURRENCY", "PERCENT");

        assertThatThrownBy(() -> setGoal(next, "TEAM", team, "150000", "CURRENCY"))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "goal.unit_not_allowed");
        assertThatThrownBy(() -> setGoal(next, "TEAM", team, "100.01", "PERCENT"))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "goal.percent_out_of_range");
        assertThatThrownBy(() -> setGoal(next, "TEAM", team, "12.345", "PERCENT"))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "goal.target_scale_invalid");
        GoalView saved = setGoal(next, "TEAM", team, "12.75", "PERCENT");
        assertThat(saved.unit()).isEqualTo("PERCENT");
        assertThat(setGoal(next, "TEAM", team, "100", "PERCENT").id()).isEqualTo(saved.id());
    }

    @Test
    void unknownSubjectIsRefused() {
        assertThatThrownBy(() -> as(manager, DefaultRoles.GESTOR, () -> plans.goalUnitOptions(current, "USER", Ids.newId())))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "goal.subject_not_found");
        assertThatThrownBy(() -> setGoal("TEAM", Ids.newId(), "10", "PERCENT"))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "goal.subject_not_found");
    }

    @Test
    void goalInAnotherUnitIsDisregardedNotReinterpreted() {
        GoalView saved = setGoal("USER", operatorX, "50000", "CURRENCY");
        assertThat(goalLine().description()).startsWith("Atingimento abaixo do gatilho").doesNotContain("desconsiderada");

        // meta gravada em % (ex.: o plano mudou de métrica depois do cadastro): não vira R$ 50.000 nem 50.000%
        system(() -> jdbcTemplate.update("update incentive.goal_target set unit = 'PERCENT' where id = ?", saved.id()));
        assertThat(goalLine().description()).isEqualTo("Sem meta definida para o período: fator 1 "
                + "(meta do período em % desconsiderada: a meta do plano é em R$; o plano não tem meta padrão)");

        // meta antiga sem unidade (anterior à V1806, não dedutível) segue a métrica do plano, como antes
        system(() -> jdbcTemplate.update("update incentive.goal_target set unit = null where id = ?", saved.id()));
        assertThat(goalLine().description()).startsWith("Atingimento abaixo do gatilho");
        assertThat(as(manager, DefaultRoles.GESTOR, () -> plans.goals(current))).extracting(GoalView::unit)
                .containsExactly((String) null);
    }

    private StatementLineView goalLine() {
        return as(manager, DefaultRoles.GESTOR, () -> statements.preview(operatorX)).projectedLines().stream()
                .filter(l -> l.component().equals("GOAL")).findFirst().orElseThrow();
    }

    private GoalView setGoal(String type, UUID subject, String target, String unit) {
        return setGoal(current, type, subject, target, unit);
    }

    private GoalView setGoal(YearMonth period, String type, UUID subject, String target, String unit) {
        return as(manager, DefaultRoles.GESTOR, () -> plans.setGoal(new GoalCommand(period, type, subject,
                new BigDecimal(target), unit)));
    }
}
