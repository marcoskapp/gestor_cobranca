package br.com.gestorcobranca.telephony;

import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases.CreateUserCommand;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.telephony.application.port.in.AgentCandidateUseCases;
import br.com.gestorcobranca.telephony.application.port.in.AgentCandidateUseCases.CandidateView;
import br.com.gestorcobranca.telephony.application.port.in.AgentUseCases.AgentView;
import br.com.gestorcobranca.telephony.application.port.in.AgentUseCases.ConfigureAgentCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Usuários habilitáveis como operador na telefonia: a lista traz só quem é ativo e tem {@code TELEPHONY_USE} (só id e
 * nome, sem exigir {@code USER_VIEW}) e a gravação do ramal recusa, pela mesma regra, usuário inexistente, desativado
 * ou sem a permissão — desabilitar quem perdeu a elegibilidade continua possível.
 */
class TelephonyAgentCandidateIT extends TelephonyTestSupport {

    @Autowired
    AgentCandidateUseCases candidates;
    @Autowired
    WebApplicationContext webContext;
    @Autowired
    JsonCodec json;

    UUID auditor;
    UUID pending;

    @BeforeEach
    void setUp() {
        provision("tcand", false);
        Map<String, UUID> roleIds = system(() -> roles.list().stream()
                .collect(java.util.stream.Collectors.toMap(RoleManagementUseCases.RoleView::code,
                        RoleManagementUseCases.RoleView::id)));
        auditor = system(() -> userManagement.create(new CreateUserCommand("Auditora A", "aud-"
                + UUID.randomUUID().toString().substring(0, 6) + "@teste.com", null,
                Set.of(roleIds.get(DefaultRoles.AUDITOR)), true, Set.of())).id());
        system(() -> jdbcTemplate.update("update identity.app_user set status = 'ACTIVE' where id = ?", auditor));
        // operador ainda sem ativação: não é elegível
        pending = system(() -> userManagement.create(new CreateUserCommand("Operador Pendente", "pend-"
                + UUID.randomUUID().toString().substring(0, 6) + "@teste.com", null,
                Set.of(roleIds.get(DefaultRoles.OPERADOR)), true, Set.of())).id());
    }

    private List<UUID> ids(List<CandidateView> views) {
        return views.stream().map(CandidateView::id).toList();
    }

    @Test
    void listShowsOnlyEligibleUsersAndSavingAppliesTheSameRule() throws Exception {
        List<CandidateView> all = asManager(() -> candidates.search(null, null, new PageQuery(0, 50))).content();
        assertThat(ids(all)).contains(operatorX, operatorY, operatorZ, supervisor).doesNotContain(auditor, pending);
        // busca sem acento e sem diferenciar maiúsculas; resolução por id
        assertThat(ids(asManager(() -> candidates.search("OPERADOR x", null, new PageQuery(0, 20))).content()))
                .containsExactly(operatorX);
        assertThat(ids(asManager(() -> candidates.search(null, Set.of(operatorY, auditor), new PageQuery(0, 20)))
                .content())).containsExactly(operatorY);

        // gravação: mesma regra
        assertThatThrownBy(() -> asManager(() -> agents.configure(auditor, new ConfigureAgentCommand("2001", true))))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "agent.user_invalid");
        assertThatThrownBy(() -> asManager(() -> agents.configure(pending, new ConfigureAgentCommand("2002", false))))
                .hasFieldOrPropertyWithValue("code", "agent.user_invalid");
        assertThatThrownBy(() -> asManager(() -> agents.configure(Ids.newId(), new ConfigureAgentCommand("2003", true))))
                .hasFieldOrPropertyWithValue("code", "agent.user_invalid");
        assertThat(count("select count(*) from telephony.agent where user_id in (?, ?)", auditor, pending)).isZero();
        AgentView supervisorAgent = asManager(() -> agents.configure(supervisor, new ConfigureAgentCommand("2004", true)));
        assertThat(supervisorAgent.active()).isTrue();

        // operador desativado no cadastro: sai da lista; manter habilitado é recusado, desabilitar continua possível
        system(() -> userManagement.disable(operatorY, "Desligamento"));
        assertThat(ids(asManager(() -> candidates.search(null, null, new PageQuery(0, 50))).content()))
                .doesNotContain(operatorY);
        assertThatThrownBy(() -> asManager(() -> agents.configure(operatorY, new ConfigureAgentCommand("ramal-y", true))))
                .hasFieldOrPropertyWithValue("code", "agent.user_invalid");
        assertThat(asManager(() -> agents.configure(operatorY, new ConfigureAgentCommand("ramal-y", false))).active())
                .isFalse();

        // quem não configura ramais não consulta a lista
        assertThatThrownBy(() -> asOperator(operatorX, () -> candidates.search(null, null, new PageQuery(0, 20))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        // cada consulta vai para o log de acesso, sem o texto buscado
        assertThat(count("select count(*) from shared.access_log where event_type = 'USER_DIRECTORY_QUERIED' "
                + "and details ->> 'resource' = 'telephony_agent_candidate' and details ->> 'q' is null"))
                .isGreaterThanOrEqualTo(4);

        // pela API: só id e nome, paginado, sem USER_VIEW
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        Set<Permission> manage = Set.of(Permission.TELEPHONY_MANAGE);
        String body = mvc.perform(get("/api/v1/telephony/agents/assignable-users").param("size", "500")
                        .with(authentication(ActorAuthentication.of(new Actor(Actor.ActorType.USER, Ids.newId(), tenant,
                                Ids.newId(), "telefonia", manage, CreditorScope.ALL)))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> page = json.mapper().readValue(body, Map.class);
        assertThat(page.get("size")).isEqualTo(AgentCandidateUseCases.MAX_PAGE_SIZE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) page.get("content");
        assertThat(content).isNotEmpty().allSatisfy(item -> assertThat(item.keySet()).containsExactlyInAnyOrder("id", "name"));
        assertThat(body).doesNotContain("@teste.com").doesNotContain("email");
        // o operador (sem TELEPHONY_MANAGE) é recusado pela API
        Set<Permission> operator = DefaultRoles.forTenant().stream().filter(d -> d.code().equals(DefaultRoles.OPERADOR))
                .findFirst().orElseThrow().permissions();
        mvc.perform(get("/api/v1/telephony/agents/assignable-users").with(authentication(ActorAuthentication.of(
                new Actor(Actor.ActorType.USER, operatorX, tenant, Ids.newId(), DefaultRoles.OPERADOR, operator,
                        CreditorScope.ALL))))).andExpect(status().isForbidden());
    }
}
