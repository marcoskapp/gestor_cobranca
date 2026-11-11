package br.com.gestorcobranca.incentive;

import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.incentive.application.port.in.AssignableUserView;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases.GoalCommand;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases.GoalView;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases.CriterionInput;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases.EvaluationCommand;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases.FormCommand;
import br.com.gestorcobranca.incentive.application.port.in.QualityUseCases.OccurrenceCommand;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Campos de pessoa do incentivo: a lista da tela (monitoria, ocorrência de conduta, meta individual) mostra só quem o
 * servidor aceita gravar — negociador ({@code CASE_WORK}) habilitado, no escopo de quem registra; na monitoria, nunca a
 * própria pessoa. A meta já existente de quem foi desligado depois continua editável.
 */
class IncentiveAssigneeIT extends IncentiveTestSupport {

    private final YearMonth current = YearMonth.now(ZONE);

    @Autowired
    QualityUseCases quality;
    @Autowired
    CommissionPlanUseCases plans;
    @Autowired
    WebApplicationContext webContext;
    @Autowired
    JsonCodec json;

    @BeforeEach
    void setUp() {
        provisionTenant("iassign");
    }

    private <T> T asManager(Supplier<T> action) {
        return as(manager, DefaultRoles.GESTOR, action);
    }

    private <T> T asSupervisor(Supplier<T> action) {
        return as(supervisor, DefaultRoles.SUPERVISOR, action);
    }

    private static List<UUID> ids(List<AssignableUserView> views) {
        return views.stream().map(AssignableUserView::id).toList();
    }

    private List<UUID> evaluable(Supplier<List<AssignableUserView>> search) {
        return ids(search.get());
    }

    private EvaluationCommand evaluation(UUID form, UUID userId) {
        return new EvaluationCommand(form, userId, "OTHER", null, LocalDate.of(2026, 9, 10),
                Map.of("ABORDAGEM", BigDecimal.TEN), null);
    }

    @Test
    void evaluableListAndRecordingFollowTheSameRule() {
        UUID form = asSupervisor(() -> quality.createForm(new FormCommand("Monitoria", null,
                List.of(new CriterionInput("ABORDAGEM", "Abordagem respeitosa", BigDecimal.ONE, false))))).id();

        // visão total (gestor): negociadores, sem ele mesmo e sem o financeiro (sem CASE_WORK)
        List<UUID> total = evaluable(() -> asManager(() -> quality.searchEvaluable(null, null, new PageQuery(0, 50)))
                .content());
        assertThat(total).contains(operatorX, operatorY, supervisor).doesNotContain(manager, finance);
        assertThatThrownBy(() -> asManager(() -> quality.evaluate(evaluation(form, finance))))
                .isInstanceOf(BusinessRuleException.class).extracting("code")
                .isEqualTo("quality_evaluation.user_not_eligible");
        assertThatThrownBy(() -> asManager(() -> quality.evaluate(evaluation(form, manager))))
                .isInstanceOf(BusinessRuleException.class).extracting("code")
                .isEqualTo("quality_evaluation.user_not_eligible");
        assertThat(asManager(() -> quality.evaluate(evaluation(form, supervisor))).userId()).isEqualTo(supervisor);

        // equipe (supervisor): só os membros; a busca por nome ignora acentos e caixa
        assertThat(evaluable(() -> asSupervisor(() -> quality.searchEvaluable(null, null, new PageQuery(0, 50)))
                .content())).containsExactlyInAnyOrder(operatorX, operatorY);
        assertThat(evaluable(() -> asSupervisor(() -> quality.searchEvaluable("OPERADOR x", null, new PageQuery(0, 50)))
                .content())).containsExactly(operatorX);
        assertThat(asSupervisor(() -> quality.evaluableUsers()).userIds()).containsExactlyInAnyOrder(operatorX, operatorY);
        assertThatThrownBy(() -> asSupervisor(() -> quality.evaluate(evaluation(form, supervisor))))
                .isInstanceOf(BusinessRuleException.class);

        // ocorrência de conduta: mesma fonte e mesma recusa
        assertThatThrownBy(() -> asManager(() -> quality.registerOccurrence(new OccurrenceCommand(finance,
                "COMPLAINT", LocalDate.of(2026, 9, 15), "Reclamação registrada pelo credor", null, null))))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("conduct.user_not_eligible");

        // desligado: some da lista, inclusive da equipe, e é recusado ao gravar
        system(() -> jdbcTemplate.update("update identity.app_user set status = 'DISABLED', disabled_at = now() where id = ?", operatorY));
        assertThat(evaluable(() -> asSupervisor(() -> quality.searchEvaluable(null, null, new PageQuery(0, 50)))
                .content())).containsExactly(operatorX);
        assertThatThrownBy(() -> asSupervisor(() -> quality.registerOccurrence(new OccurrenceCommand(operatorY,
                "COMPLAINT", LocalDate.of(2026, 9, 15), "Reclamação registrada pelo credor", null, null))))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("conduct.user_not_eligible");
        // fora da equipe continua 403 (escopo), como antes
        assertThatThrownBy(() -> asSupervisor(() -> quality.evaluate(evaluation(form, manager))))
                .isInstanceOf(ScopeAccessDeniedException.class);

        // cada busca vai para o log de acesso, sem o texto buscado
        assertThat(count("select count(*) from shared.access_log where event_type = 'USER_DIRECTORY_QUERIED' "
                + "and details ->> 'resource' = 'incentive_assignee' and details ->> 'purpose' = 'EVALUATION' "
                + "and details::text not ilike '%operador x%'")).isGreaterThanOrEqualTo(4);
    }

    @Test
    void newGoalRequiresAnEligibleNegotiatorButExistingGoalStaysEditable() {
        List<UUID> listed = ids(asManager(() -> plans.searchGoalUsers(null, null, new PageQuery(0, 50))).content());
        assertThat(listed).contains(operatorX, operatorY, manager).doesNotContain(finance);
        assertThatThrownBy(() -> setGoal(finance, "1000"))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("goal.subject_not_eligible");
        assertThatThrownBy(() -> setGoal(Ids.newId(), "1000"))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("goal.subject_not_eligible");
        GoalView saved = setGoal(operatorX, "50000");

        // desligado depois: sai da lista, a meta existente continua legível e editável
        system(() -> jdbcTemplate.update("update identity.app_user set status = 'DISABLED', disabled_at = now() where id = ?", operatorX));
        assertThat(ids(asManager(() -> plans.searchGoalUsers(null, null, new PageQuery(0, 50))).content()))
                .doesNotContain(operatorX);
        assertThat(asManager(() -> plans.goals(current))).extracting(GoalView::id).contains(saved.id());
        assertThat(setGoal(operatorX, "60000").id()).isEqualTo(saved.id());
        // nova meta para outro desligado: recusada
        system(() -> jdbcTemplate.update("update identity.app_user set status = 'DISABLED', disabled_at = now() where id = ?", operatorY));
        assertThatThrownBy(() -> setGoal(operatorY, "1000")).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void apiReturnsOnlyIdAndNameWithoutUserView() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        Set<Permission> supervisorPermissions = DefaultRoles.forTenant().stream()
                .filter(d -> d.code().equals(DefaultRoles.SUPERVISOR)).findFirst().orElseThrow().permissions();
        Set<Permission> withoutUserView = new java.util.HashSet<>(supervisorPermissions);
        withoutUserView.remove(Permission.USER_VIEW);
        ActorAuthentication auth = ActorAuthentication.of(new Actor(Actor.ActorType.USER, supervisor, tenant,
                Ids.newId(), DefaultRoles.SUPERVISOR, withoutUserView, CreditorScope.ALL));
        String body = mvc.perform(get("/api/v1/incentive/quality/evaluable-users/search").param("size", "500")
                        .with(authentication(auth)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> page = json.mapper().readValue(body, Map.class);
        assertThat(page.get("size")).isEqualTo(AssignableUserView.MAX_PAGE_SIZE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) page.get("content");
        assertThat(content).hasSize(2)
                .allSatisfy(item -> assertThat(item.keySet()).containsExactlyInAnyOrder("id", "name"));
        assertThat(body).doesNotContain("@teste.com");
        // metas exigem COMMISSION_PLAN_MANAGE
        mvc.perform(get("/api/v1/incentive/goals/assignable-users").with(authentication(auth)))
                .andExpect(status().isForbidden());
    }

    private GoalView setGoal(UUID subject, String target) {
        return asManager(() -> plans.setGoal(new GoalCommand(current, "USER", subject, new BigDecimal(target),
                "CURRENCY")));
    }
}
