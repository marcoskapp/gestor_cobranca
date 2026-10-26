package br.com.gestorcobranca.incentive;

import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases.GoalCommand;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases.GoalView;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases.PlanCommand;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases.PlanView;
import br.com.gestorcobranca.incentive.application.port.in.PeriodUseCases;
import br.com.gestorcobranca.incentive.domain.model.plan.StandardPlans;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Descarte de rascunho de plano e remoção de meta (lacunas da API encontradas nas telas): só
 * rascunhos saem, versões publicadas continuam imutáveis (inclusive no banco), metas só saem de
 * período aberto, e tudo fica na trilha de auditoria.
 */
class PlanDraftAndGoalRemovalIT extends IncentiveTestSupport {

    private static final YearMonth AUGUST = YearMonth.of(2026, 8);

    @Autowired
    CommissionPlanUseCases plans;
    @Autowired
    PeriodUseCases periods;

    @BeforeEach
    void setUp() {
        provisionTenant("draft");
    }

    @Test
    void draftIsDiscardedAndPublishedVersionIsKept() {
        PlanView standard = as(manager, DefaultRoles.GESTOR, () -> plans.list()).stream()
                .filter(p -> p.code().equals(StandardPlans.PADRAO_MERCADO)).findFirst().orElseThrow();
        assertThat(standard.status()).isEqualTo("PUBLISHED");

        PlanView draft = as(manager, DefaultRoles.GESTOR, () -> plans.newVersion(standard.id()));
        assertThat(draft.version()).isEqualTo(2);
        as(manager, DefaultRoles.GESTOR, () -> {
            plans.discardDraft(draft.id());
            return null;
        });

        assertThatThrownBy(() -> as(manager, DefaultRoles.GESTOR, () -> plans.get(draft.id())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(count("select count(*) from shared.audit_event where action = 'commission_plan.discarded' and entity_id = ?",
                draft.id())).isEqualTo(1);
        // o rascunho descartado libera uma nova versão (um rascunho por código)
        PlanView again = as(manager, DefaultRoles.GESTOR, () -> plans.newVersion(standard.id()));
        assertThat(again.version()).isEqualTo(2);

        assertThatThrownBy(() -> as(manager, DefaultRoles.GESTOR, () -> {
            plans.discardDraft(standard.id());
            return null;
        })).isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "plan.not_draft");
        assertThat(as(manager, DefaultRoles.GESTOR, () -> plans.get(standard.id())).status()).isEqualTo("PUBLISHED");

        assertThatThrownBy(() -> as(operatorX, DefaultRoles.OPERADOR, () -> {
            plans.discardDraft(again.id());
            return null;
        })).isInstanceOf(ScopeAccessDeniedException.class);
    }

    @Test
    void newPlanDraftCanBeDiscardedEntirely() {
        PlanView created = as(manager, DefaultRoles.GESTOR, () -> plans.createDraft(new PlanCommand("CAMPANHA_TESTE",
                "Campanha de teste", null, 10, StandardPlans.padraoMercado())));
        as(manager, DefaultRoles.GESTOR, () -> {
            plans.discardDraft(created.id());
            return null;
        });
        assertThat(as(manager, DefaultRoles.GESTOR, () -> plans.list())).noneMatch(p -> p.code().equals("CAMPANHA_TESTE"));
    }

    @Test
    void goalIsRemovedOnlyWhileThePeriodIsOpen() {
        GoalView goal = as(manager, DefaultRoles.GESTOR, () -> plans.setGoal(new GoalCommand(AUGUST, "USER", operatorX,
                new BigDecimal("80000"), "CURRENCY")));
        GoalView other = as(manager, DefaultRoles.GESTOR, () -> plans.setGoal(new GoalCommand(AUGUST, "TEAM", team,
                new BigDecimal("200000"), "CURRENCY")));
        as(manager, DefaultRoles.GESTOR, () -> {
            plans.removeGoal(goal.id());
            return null;
        });
        assertThat(as(manager, DefaultRoles.GESTOR, () -> plans.goals(AUGUST))).extracting(GoalView::id)
                .containsExactly(other.id());
        assertThat(count("select count(*) from shared.audit_event where action = 'commission_goal.removed' and entity_id = ?",
                goal.id())).isEqualTo(1);
        assertThatThrownBy(() -> as(manager, DefaultRoles.GESTOR, () -> {
            plans.removeGoal(Ids.newId());
            return null;
        })).isInstanceOf(ResourceNotFoundException.class);

        as(admin, DefaultRoles.ADMIN, () -> periods.close(AUGUST));
        assertThatThrownBy(() -> as(manager, DefaultRoles.GESTOR, () -> {
            plans.removeGoal(other.id());
            return null;
        })).isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "goal.period_closed");
    }
}
