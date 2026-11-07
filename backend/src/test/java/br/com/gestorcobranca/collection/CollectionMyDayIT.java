package br.com.gestorcobranca.collection;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.PromiseInput;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.RecordInteractionCommand;
import br.com.gestorcobranca.collection.application.port.in.MyDayQueries;
import br.com.gestorcobranca.collection.application.port.in.MyDayQueries.MyDayView;
import br.com.gestorcobranca.collection.application.port.in.MyDayQueries.OperatorDay;
import br.com.gestorcobranca.collection.application.port.in.MyDayQueries.TeamDay;
import br.com.gestorcobranca.collection.application.port.in.TaskUseCases.ScheduleTaskCommand;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Seu dia" do início (D8): contagens do operador (casos, retornos, promessas e o próprio resultado
 * do dia) e das equipes do supervisor (filas e atrasos), com escopo por credor e autorização na
 * camada web; e a duração da interação, que não aceita fim no futuro.
 */
class CollectionMyDayIT extends CollectionTestSupport {

    @Autowired
    MyDayQueries myDay;
    @Autowired
    WebApplicationContext webApplicationContext;

    MockMvc mvc;
    CaseView caseA;
    LocalDate today;

    @BeforeEach
    void setUp() {
        provision("myd");
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        asManager(() -> queues.create(queue("BANCO_A", 10, creditorA, "PULL", null, Set.of(team))));
        UUID debtor = debtor("526.018.159-06");
        load(creditorA, portfolioA, debtor, "300.00");
        caseA = activeCase(debtor, portfolioA);
        UUID waiting = debtor("083.016.613-05");
        load(creditorA, portfolioA, waiting, "120.00");
        awaitIdle("CollectionEventListeners");
        today = LocalDate.now(ZONE);
    }

    @Test
    void operatorDayCountsOwnWorkAndFollowsTheBusinessDay() {
        record(operatorX, caseA.id(), interaction("PROMISE", new PromiseInput(new BigDecimal("50.00"), today), null, null));
        asOperator(operatorX, () -> tasks.schedule(caseA.id(), new ScheduleTaskCommand("CALLBACK",
                today.plusDays(1).atTime(LocalTime.of(10, 0)), null, null)));

        OperatorDay now = asOperator(operatorX, () -> myDay.myDay()).operator();
        assertThat(now.assignedCases()).isEqualTo(1);
        assertThat(now.promisesDueToday()).isEqualTo(1);
        assertThat(now.promisesAwaitingPayment()).isZero();
        assertThat(now.interactionsToday()).isEqualTo(1);
        assertThat(now.effectiveContactsToday()).isEqualTo(1);
        assertThat(now.promisesRecordedToday()).isEqualTo(1);
        assertThat(now.callbacksToday()).as("retorno é amanhã").isZero();
        assertThat(now.callbacksOverdue()).isZero();

        // amanhã às 9h: o retorno é de hoje, a promessa venceu ontem e o resultado do dia recomeça
        shiftTo(today.plusDays(1).atTime(9, 0).atZone(ZONE).toInstant());
        OperatorDay tomorrow = asOperator(operatorX, () -> myDay.myDay()).operator();
        assertThat(tomorrow.callbacksToday()).isEqualTo(1);
        assertThat(tomorrow.callbacksOverdue()).isZero();
        assertThat(tomorrow.promisesDueToday()).isZero();
        assertThat(tomorrow.promisesAwaitingPayment()).isEqualTo(1);
        assertThat(tomorrow.interactionsToday()).isZero();

        // às 11h o retorno das 10h está atrasado
        shiftTo(today.plusDays(1).atTime(11, 0).atZone(ZONE).toInstant());
        OperatorDay late = asOperator(operatorX, () -> myDay.myDay()).operator();
        assertThat(late.callbacksToday()).isZero();
        assertThat(late.callbacksOverdue()).isEqualTo(1);

        // outro operador não vê o trabalho de X
        OperatorDay other = asOperator(operatorY, () -> myDay.myDay()).operator();
        assertThat(other.assignedCases()).isZero();
        assertThat(other.callbacksOverdue()).isZero();
        assertThat(asOperator(operatorY, () -> myDay.myDay()).team()).as("operador não distribui casos").isNull();
    }

    @Test
    void supervisorSeesTheTeamsHeLeadsWithinTheCreditorScope() {
        record(operatorX, caseA.id(), interaction("PROMISE", new PromiseInput(new BigDecimal("50.00"), today), null, null));

        MyDayView view = asSupervisor(() -> myDay.myDay());
        TeamDay team = view.team();
        assertThat(team).isNotNull();
        assertThat(team.teams()).isEqualTo(1);
        assertThat(team.members()).isEqualTo(2);
        assertThat(team.assignedCases()).isEqualTo(1);
        assertThat(team.promisesDueToday()).isEqualTo(1);
        assertThat(team.queues()).singleElement().satisfies(q -> {
            assertThat(q.name()).isEqualTo("Fila BANCO_A");
            assertThat(q.waiting()).as("o outro caso aguarda na fila").isEqualTo(1);
        });

        // supervisor só com o credor B: nada do credor A
        TeamDay scoped = as(supervisor, DefaultRoles.SUPERVISOR, CreditorScope.of(Set.of(creditorB)),
                () -> myDay.myDay()).team();
        assertThat(scoped.assignedCases()).isZero();
        assertThat(scoped.promisesDueToday()).isZero();
        assertThat(scoped.queues()).isEmpty();

        // gestor sem equipe supervisionada: sem bloco da equipe
        assertThat(asManager(() -> myDay.myDay()).team()).isNull();
    }

    @Test
    void endpointRequiresCaseWorkOrCaseAssignAndCarriesNoDebtorData() throws Exception {
        mvc.perform(get("/api/v1/collection/my-day")
                        .with(authentication(ActorAuthentication.of(actor(operatorX, EnumSet.of(Permission.CASE_VIEW),
                                CreditorScope.ALL)))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/collection/my-day")
                        .with(authentication(ActorAuthentication.of(operator(operatorX, CreditorScope.ALL)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(today.toString()))
                .andExpect(jsonPath("$.operator.assignedCases").value(0))
                .andExpect(jsonPath("$.operator.debtorId").doesNotExist())
                .andExpect(jsonPath("$.team").doesNotExist());
    }

    @Test
    void interactionEndInTheFutureIsRejected() {
        Instant start = Instant.now().minusSeconds(120);
        RecordInteractionCommand future = new RecordInteractionCommand("PHONE", "OUTBOUND", null, "NO_ANSWER", null, null,
                start, Instant.now().plus(Duration.ofHours(1)), null, null, null, null, null, null);
        assertThatThrownBy(() -> record(operatorX, caseA.id(), future))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("interaction.ended_in_future"));

        RecordInteractionCommand ok = new RecordInteractionCommand("PHONE", "OUTBOUND", null, "NO_ANSWER", null, null,
                start, Instant.now(), null, null, null, null, null, null);
        assertThat(record(operatorX, caseA.id(), ok).interaction().durationSeconds()).isBetween(119, 125);
    }

    private Actor actor(UUID userId, Set<Permission> permissions, CreditorScope scope) {
        return new Actor(Actor.ActorType.USER, userId, tenant, Ids.newId(), "TESTE", permissions, scope);
    }

    private Actor operator(UUID userId, CreditorScope scope) {
        return actor(userId, DefaultRoles.forTenant().stream().filter(d -> d.code().equals(DefaultRoles.OPERADOR))
                .findFirst().orElseThrow().permissions(), scope);
    }

    private void shiftTo(Instant target) {
        clock.reset();
        clock.advance(Duration.between(Instant.now(), target));
    }
}
