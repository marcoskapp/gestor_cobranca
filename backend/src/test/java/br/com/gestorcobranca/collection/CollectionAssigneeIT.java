package br.com.gestorcobranca.collection;

import br.com.gestorcobranca.collection.application.port.in.CaseAssigneeUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseAssigneeUseCases.AssigneeQuery;
import br.com.gestorcobranca.collection.application.port.in.CaseAssigneeUseCases.AssigneeView;
import br.com.gestorcobranca.collection.application.port.in.CaseAssigneeUseCases.Purpose;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.AssignCommand;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.RedistributeCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeConfigUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.OpenDisputeCommand;
import br.com.gestorcobranca.collection.application.port.in.TaskUseCases.ScheduleTaskCommand;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
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
 * Usuários atribuíveis da cobrança: a lista de cada campo mostra exatamente quem a gravação aceita (atribuir caso,
 * responsável da tarefa, redistribuição de casos, grupo e redistribuição de contestações), só com id, nome e situação,
 * sem exigir {@code USER_VIEW} e com o log de acesso.
 */
class CollectionAssigneeIT extends CollectionTestSupport {

    @Autowired
    CaseAssigneeUseCases assignees;
    @Autowired
    DisputeConfigUseCases disputeConfig;
    @Autowired
    WebApplicationContext webContext;
    @Autowired
    JsonCodec json;

    @BeforeEach
    void setUp() {
        provision("cassign");
    }

    private CaseView caseOfA(String cpf) {
        UUID debtor = debtor(cpf);
        load(creditorA, portfolioA, debtor, "800.00");
        return activeCase(debtor, portfolioA);
    }

    private List<UUID> ids(Supplier<List<AssigneeView>> search) {
        return search.get().stream().map(AssigneeView::id).toList();
    }

    private List<AssigneeView> search(Purpose purpose, UUID caseId, UUID creditorId, UUID from) {
        return assignees.search(new AssigneeQuery(purpose, caseId, creditorId, from, null, null), new PageQuery(0, 50))
                .content();
    }

    private <T> T asScopedSupervisor(Set<UUID> creditors, Supplier<T> action) {
        return as(Ids.newId(), DefaultRoles.SUPERVISOR, CreditorScope.of(creditors), action);
    }

    private void deactivate(UUID userId) {
        system(() -> userManagement.disable(userId, "Desligamento"));
    }

    @Test
    void callbackFromTabulationForSomeoneElseRequiresCaseAssign() {
        CaseView c = caseOfA("390.533.447-05");
        asSupervisor(() -> work.assign(c.id(), new AssignCommand(operatorX, null, null)));
        // o retorno da tabulação para outra pessoa é atribuir: sem CASE_ASSIGN, barrado no servidor (decisão 1092)
        assertThatThrownBy(() -> record(operatorX, c.id(), interaction("CALLBACK", null,
                new InteractionUseCases.CallbackInput(LocalDateTime.now(ZONE).plusHours(2).withNano(0), operatorY, null), null)))
                .isInstanceOf(ScopeAccessDeniedException.class);
        // para o próprio dono do caso (o padrão) continua permitido
        assertThat(record(operatorX, c.id(), interaction("CALLBACK", null,
                new InteractionUseCases.CallbackInput(LocalDateTime.now(ZONE).plusHours(2).withNano(0), operatorX, null), null))
                .callback().assigneeUserId()).isEqualTo(operatorX);
    }

    @Test
    void caseOperatorAndTaskAssigneeListMatchesTheWriteRule() {
        CaseView c = caseOfA("529.982.247-25");
        List<UUID> workers = asSupervisor(() -> ids(() -> search(Purpose.CASE_WORKER, c.id(), null, null)));
        // Z só atende o credor B: fora da lista e recusado ao gravar
        assertThat(workers).contains(operatorX, operatorY, supervisor).doesNotContain(operatorZ);
        assertThatThrownBy(() -> asSupervisor(() -> work.assign(c.id(), new AssignCommand(operatorZ, null, null))))
                .hasFieldOrPropertyWithValue("code", "case.assignee_out_of_scope");
        assertThatThrownBy(() -> asSupervisor(() -> tasks.schedule(c.id(), new ScheduleTaskCommand("CALLBACK",
                LocalDateTime.now(ZONE).plusDays(1).withNano(0), operatorZ, null))))
                .hasFieldOrPropertyWithValue("code", "task.assignee_invalid");
        // escolher outro responsável no retorno é atribuir: o operador sem CASE_ASSIGN é barrado no servidor
        assertThatThrownBy(() -> asOperator(operatorX, () -> tasks.schedule(c.id(), new ScheduleTaskCommand("CALLBACK",
                LocalDateTime.now(ZONE).plusDays(1).withNano(0), operatorY, null))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        // quem está na lista é aceito
        assertThat(asSupervisor(() -> work.assign(c.id(), new AssignCommand(operatorY, null, null))).assigneeUserId())
                .isEqualTo(operatorY);
        assertThat(asSupervisor(() -> tasks.schedule(c.id(), new ScheduleTaskCommand("CALLBACK",
                LocalDateTime.now(ZONE).plusDays(1).withNano(0), operatorX, null))).assigneeUserId()).isEqualTo(operatorX);

        // inativo sai da lista e é recusado
        deactivate(operatorX);
        assertThat(asSupervisor(() -> ids(() -> search(Purpose.CASE_WORKER, c.id(), null, null)))).doesNotContain(operatorX);
        assertThatThrownBy(() -> asSupervisor(() -> work.assign(c.id(), new AssignCommand(operatorX, null, null))))
                .hasFieldOrPropertyWithValue("code", "case.assignee_invalid");

        // busca por nome (sem acento/maiúsculas) e resolução por ids
        assertThat(asSupervisor(() -> assignees.search(new AssigneeQuery(Purpose.CASE_WORKER, c.id(), null, null,
                "operador y", null), new PageQuery(0, 20))).content()).extracting(AssigneeView::id)
                .containsExactly(operatorY);
        assertThat(asSupervisor(() -> assignees.search(new AssigneeQuery(Purpose.CASE_WORKER, c.id(), null, null, null,
                Set.of(operatorY, operatorZ)), new PageQuery(0, 20))).content()).extracting(AssigneeView::id)
                .containsExactly(operatorY);

        // sem CASE_ASSIGN: recusado; caso fora do escopo: inexistente
        assertThatThrownBy(() -> asOperator(operatorY, () -> search(Purpose.CASE_WORKER, c.id(), null, null)))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> asScopedSupervisor(Set.of(creditorB), () -> search(Purpose.CASE_WORKER, c.id(), null,
                null))).isInstanceOf(br.com.gestorcobranca.shared.domain.ResourceNotFoundException.class);

        // log de acesso, sem o texto buscado
        assertThat(count("select count(*) from shared.access_log where event_type = 'USER_DIRECTORY_QUERIED' "
                + "and actor_id = ? and details ->> 'purpose' = 'CASE_WORKER' and details ->> 'caseId' = ? "
                + "and details ->> 'q' is null", supervisor, c.id().toString())).isGreaterThanOrEqualTo(4);
    }

    @Test
    void caseRedistributionSourcesIncludeWhoLeftAndTargetsMatchTheWriteRule() {
        CaseView c = caseOfA("111.444.777-35");
        asSupervisor(() -> work.assign(c.id(), new AssignCommand(operatorY, null, null)));
        deactivate(operatorY);

        // origem: quem tem casos ativos no escopo, mesmo inativo (carteira de quem saiu)
        List<AssigneeView> sources = asSupervisor(() -> search(Purpose.CASE_REDISTRIBUTION_SOURCE, null, null, null));
        assertThat(sources).filteredOn(v -> v.id().equals(operatorY)).singleElement()
                .satisfies(v -> assertThat(v.active()).isFalse());
        assertThat(sources).extracting(AssigneeView::id).doesNotContain(operatorX, operatorZ);
        // fora do escopo do ator, a carteira de Y (credor A) não aparece
        assertThat(asScopedSupervisor(Set.of(creditorB), () -> ids(() -> search(Purpose.CASE_REDISTRIBUTION_SOURCE,
                null, null, null)))).doesNotContain(operatorY);

        // destino: ativos com CASE_WORK, que alcançam o escopo do ator, exceto a origem
        assertThat(asSupervisor(() -> ids(() -> search(Purpose.CASE_REDISTRIBUTION_TARGET, null, null, operatorY))))
                .contains(operatorX, operatorZ, supervisor).doesNotContain(operatorY);
        Set<UUID> onlyA = Set.of(creditorA);
        assertThat(asScopedSupervisor(onlyA, () -> ids(() -> search(Purpose.CASE_REDISTRIBUTION_TARGET, null, null,
                operatorY)))).contains(operatorX).doesNotContain(operatorZ);
        assertThatThrownBy(() -> asScopedSupervisor(onlyA, () -> work.redistribute(new RedistributeCommand(operatorY,
                Set.of(operatorZ), "Desligamento")))).hasFieldOrPropertyWithValue("code", "case.assignee_invalid");
        var result = asScopedSupervisor(onlyA, () -> work.redistribute(new RedistributeCommand(operatorY,
                Set.of(operatorX), "Desligamento")));
        assertThat(result.assigned()).isEqualTo(1);
        assertThat(asSupervisor(() -> ids(() -> search(Purpose.CASE_REDISTRIBUTION_SOURCE, null, null, null))))
                .contains(operatorX).doesNotContain(operatorY);
    }

    @Test
    void disputePoolAndRedistributionListsMatchTheWriteRule() {
        // grupo do credor A: Z (só credor B) não entra; quem já está no grupo sai da lista
        assertThat(asSupervisor(() -> ids(() -> search(Purpose.DISPUTE_POOL_MEMBER, null, creditorA, null))))
                .contains(operatorX, operatorY, supervisor).doesNotContain(operatorZ);
        assertThatThrownBy(() -> asSupervisor(() -> disputeConfig.addPoolMember(creditorA, operatorZ)))
                .hasFieldOrPropertyWithValue("code", "dispute_pool.user_invalid");
        asSupervisor(() -> disputeConfig.addPoolMember(creditorA, operatorX));
        assertThat(asSupervisor(() -> ids(() -> search(Purpose.DISPUTE_POOL_MEMBER, null, creditorA, null))))
                .doesNotContain(operatorX);
        assertThatThrownBy(() -> asSupervisor(() -> disputeConfig.addPoolMember(creditorA, operatorX)))
                .isInstanceOf(ConflictException.class);
        // grupo geral: basta trabalhar contestações; exige todos os credores de quem configura
        assertThat(asSupervisor(() -> ids(() -> search(Purpose.DISPUTE_POOL_MEMBER, null, null, null))))
                .contains(operatorZ);
        assertThatThrownBy(() -> asScopedSupervisor(Set.of(creditorA), () -> search(Purpose.DISPUTE_POOL_MEMBER, null,
                null, null))).isInstanceOf(ScopeAccessDeniedException.class);

        // contestação do credor A com X
        CaseView c = caseOfA("390.533.447-05");
        DisputeView d = asOperator(operatorX, () -> disputes.open(c.id(), new OpenDisputeCommand(Set.of(), "OTHER",
                "DEBTOR", "Contestação de teste", null, null)));
        asSupervisor(() -> disputes.assign(d.id(), operatorX, "Distribuição"));
        assertThat(asSupervisor(() -> ids(() -> search(Purpose.DISPUTE_REDISTRIBUTION_SOURCE, null, null, null))))
                .contains(operatorX).doesNotContain(operatorY);
        // destino: acesso aos credores das contestações da origem (A) — Z fica de fora e é recusado
        assertThat(asSupervisor(() -> ids(() -> search(Purpose.DISPUTE_REDISTRIBUTION_TARGET, null, null, operatorX))))
                .contains(operatorY, supervisor).doesNotContain(operatorX, operatorZ);
        assertThatThrownBy(() -> asSupervisor(() -> disputes.redistribute(operatorX, operatorZ, "Férias")))
                .hasFieldOrPropertyWithValue("code", "dispute.assignee_invalid");
        assertThat(asSupervisor(() -> disputes.redistribute(operatorX, operatorY, "Férias")).moved()).isEqualTo(1);

        // sem DISPUTE_MANAGE: recusado
        assertThatThrownBy(() -> asOperator(operatorY, () -> search(Purpose.DISPUTE_REDISTRIBUTION_SOURCE, null, null,
                null))).isInstanceOf(ScopeAccessDeniedException.class);
    }

    @Test
    void apiReturnsOnlyIdNameAndSituationWithoutUserView() throws Exception {
        CaseView c = caseOfA("153.509.460-56");
        Set<Permission> supervisorPermissions = DefaultRoles.forTenant().stream()
                .filter(d -> d.code().equals(DefaultRoles.SUPERVISOR)).findFirst().orElseThrow().permissions();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        // perfil com CASE_ASSIGN e sem USER_VIEW (o cadastro de usuários) basta
        Set<Permission> withoutUserView = new java.util.HashSet<>(supervisorPermissions);
        withoutUserView.remove(Permission.USER_VIEW);
        ActorAuthentication auth = ActorAuthentication.of(new Actor(Actor.ActorType.USER, supervisor, tenant, Ids.newId(),
                DefaultRoles.SUPERVISOR, withoutUserView, CreditorScope.ALL));
        String body = mvc.perform(get("/api/v1/collection/assignable-users").param("purpose", "CASE_WORKER")
                        .param("caseId", c.id().toString()).param("size", "500").with(authentication(auth)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> page = json.mapper().readValue(body, Map.class);
        assertThat(page.get("size")).isEqualTo(CaseAssigneeUseCases.MAX_PAGE_SIZE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) page.get("content");
        assertThat(content).isNotEmpty()
                .allSatisfy(item -> assertThat(item.keySet()).containsExactlyInAnyOrder("id", "name", "active"));
        assertThat(body).doesNotContain("@teste.com").doesNotContain("email");
        // operador (sem CASE_ASSIGN nem DISPUTE_MANAGE) é recusado pela API
        Set<Permission> operator = DefaultRoles.forTenant().stream().filter(d -> d.code().equals(DefaultRoles.OPERADOR))
                .findFirst().orElseThrow().permissions();
        mvc.perform(get("/api/v1/collection/assignable-users").param("purpose", "CASE_WORKER")
                .param("caseId", c.id().toString()).with(authentication(ActorAuthentication.of(new Actor(
                        Actor.ActorType.USER, operatorX, tenant, Ids.newId(), DefaultRoles.OPERADOR, operator,
                        CreditorScope.ALL))))).andExpect(status().isForbidden());
    }
}
