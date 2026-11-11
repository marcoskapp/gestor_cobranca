package br.com.gestorcobranca.compliance;

import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestAssigneeUseCases;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestAssigneeUseCases.AssigneeView;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases.OpenRequestCommand;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases.RequestView;
import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.ProvisionTenantCommand;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases.CreateUserCommand;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Responsável por solicitação de titular: a lista traz só quem é ativo, atende solicitações de titulares e tem acesso a
 * todos os credores (só id e nome, sem exigir {@code USER_VIEW}); atribuir e iniciar com responsável recusam, pela
 * mesma regra, usuário inexistente, desativado, sem a permissão ou com acesso a parte dos credores.
 */
class DataSubjectRequestAssigneeIT extends IntegrationTest {

    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    TenantProvisioning provisioning;
    @Autowired
    RoleManagementUseCases roles;
    @Autowired
    UserManagementUseCases userManagement;
    @Autowired
    CreditorUseCases creditors;
    @Autowired
    DataSubjectRequestUseCases requests;
    @Autowired
    DataSubjectRequestAssigneeUseCases assignees;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    WebApplicationContext webContext;
    @Autowired
    JsonCodec json;

    UUID tenant;
    UUID officer;
    UUID officer2;
    UUID scopedOfficer;
    UUID operator;
    UUID disabledOfficer;

    @BeforeEach
    void setUp() {
        String slug = "dsra-" + UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> provisioning.provision(
                new ProvisionTenantCommand(slug, "Assessoria DSR Ltda", "dsra", "11.444.777/0001-61", "Admin",
                        "admin@" + slug + ".test"))).tenant().id();
        UUID creditor = system(() -> creditors.create(new CreditorCommand("Banco A S.A.", "Banco A", "11.222.333/0001-81",
                null, null)).id());
        Map<String, UUID> roleIds = system(() -> roles.list().stream()
                .collect(Collectors.toMap(RoleManagementUseCases.RoleView::code, RoleManagementUseCases.RoleView::id)));
        UUID compliance = roleIds.get(DefaultRoles.COMPLIANCE);
        officer = user("Encarregada Joana", "j", compliance, true, Set.of());
        officer2 = user("Encarregado Bruno", "b", compliance, true, Set.of());
        scopedOfficer = user("Encarregado Parcial", "p", compliance, false, Set.of(creditor));
        operator = user("Operador O", "o", roleIds.get(DefaultRoles.OPERADOR), true, Set.of());
        disabledOfficer = user("Encarregado Desligado", "d", compliance, true, Set.of());
        system(() -> userManagement.disable(disabledOfficer, "Desligamento"));
    }

    private UUID user(String name, String key, UUID roleId, boolean allCreditors, Set<UUID> creditorIds) {
        UUID id = system(() -> userManagement.create(new CreateUserCommand(name, key + "-"
                + UUID.randomUUID().toString().substring(0, 6) + "@teste.com", null, Set.of(roleId), allCreditors,
                creditorIds)).id());
        system(() -> jdbcTemplate.update("update identity.app_user set status = 'ACTIVE' where id = ?", id));
        return id;
    }

    private <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    private static Set<Permission> permissionsOf(String role) {
        return DefaultRoles.forTenant().stream().filter(d -> d.code().equals(role)).findFirst().orElseThrow()
                .permissions();
    }

    private Actor officerActor(CreditorScope scope) {
        return new Actor(Actor.ActorType.USER, officer, tenant, Ids.newId(), DefaultRoles.COMPLIANCE,
                permissionsOf(DefaultRoles.COMPLIANCE), scope);
    }

    private <T> T asOfficer(Supplier<T> action) {
        return tenantExecution.callAs(officerActor(CreditorScope.ALL), action);
    }

    private long count(String sql, Object... args) {
        return system(() -> jdbcTemplate.queryForObject(sql, Long.class, args));
    }

    private static List<UUID> ids(List<AssigneeView> views) {
        return views.stream().map(AssigneeView::id).toList();
    }

    private RequestView verified() {
        return asOfficer(() -> {
            RequestView opened = requests.open(new OpenRequestCommand("CONFIRMATION", "EMAIL", "Maria Silva", null, null,
                    "Confirma se tratam meus dados?", null));
            return requests.verifyIdentity(opened.id(), null);
        });
    }

    @Test
    void listShowsOnlyEligibleUsersAndAssignAndStartApplyTheSameRule() throws Exception {
        List<AssigneeView> all = asOfficer(() -> assignees.search(null, null, new PageQuery(0, 50))).content();
        assertThat(ids(all)).contains(officer, officer2)
                .doesNotContain(scopedOfficer, operator, disabledOfficer);
        // busca sem acento e sem diferenciar maiúsculas; resolução por id só entre os elegíveis
        assertThat(ids(asOfficer(() -> assignees.search("JOANA", null, new PageQuery(0, 20))).content()))
                .containsExactly(officer);
        assertThat(ids(asOfficer(() -> assignees.search(null, Set.of(officer2, scopedOfficer), new PageQuery(0, 20)))
                .content())).containsExactly(officer2);

        RequestView request = verified();
        // iniciar com responsável inelegível: recusado e nada muda
        assertThatThrownBy(() -> asOfficer(() -> requests.start(request.id(), operator)))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "dsr.assignee_invalid");
        assertThat(asOfficer(() -> requests.get(request.id())).status()).isEqualTo("IDENTITY_VERIFIED");
        RequestView started = asOfficer(() -> requests.start(request.id(), officer));
        assertThat(started.assignedTo()).isEqualTo(officer);

        // atribuir: mesma regra
        for (UUID invalid : List.of(scopedOfficer, operator, disabledOfficer, Ids.newId())) {
            assertThatThrownBy(() -> asOfficer(() -> requests.assign(request.id(), invalid)))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasFieldOrPropertyWithValue("code", "dsr.assignee_invalid");
        }
        assertThat(asOfficer(() -> requests.assign(request.id(), officer2)).assignedTo()).isEqualTo(officer2);
        assertThat(count("select count(*) from shared.audit_event where entity_id = ? and action = "
                + "'data_subject_request.assigned'", request.id())).isEqualTo(1);

        // a lista exige o mesmo alcance exigido para atribuir
        assertThatThrownBy(() -> tenantExecution.callAs(officerActor(CreditorScope.of(Set.of(Ids.newId()))),
                () -> assignees.search(null, null, new PageQuery(0, 20)))).isInstanceOf(ScopeAccessDeniedException.class);
        // cada consulta vai para o log de acesso, sem o texto buscado
        assertThat(count("select count(*) from shared.access_log where event_type = 'USER_DIRECTORY_QUERIED' "
                + "and details ->> 'resource' = 'data_subject_request_assignee' and details ->> 'q' is null"))
                .isGreaterThanOrEqualTo(3);

        // pela API: só id e nome, paginado, sem USER_VIEW
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
        Actor withoutUserView = new Actor(Actor.ActorType.USER, officer, tenant, Ids.newId(), "titulares",
                Set.of(Permission.DATA_SUBJECT_REQUEST_MANAGE), CreditorScope.ALL);
        String body = mvc.perform(get("/api/v1/compliance/data-subject-requests/assignable-users").param("size", "500")
                        .with(authentication(ActorAuthentication.of(withoutUserView))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        @SuppressWarnings("unchecked")
        Map<String, Object> page = json.mapper().readValue(body, Map.class);
        assertThat(page.get("size")).isEqualTo(DataSubjectRequestAssigneeUseCases.MAX_PAGE_SIZE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) page.get("content");
        assertThat(content).isNotEmpty().allSatisfy(item -> assertThat(item.keySet()).containsExactlyInAnyOrder("id", "name"));
        assertThat(body).doesNotContain("@teste.com").doesNotContain("email");
        // sem DATA_SUBJECT_REQUEST_MANAGE: recusado pela API
        mvc.perform(get("/api/v1/compliance/data-subject-requests/assignable-users").with(authentication(
                ActorAuthentication.of(new Actor(Actor.ActorType.USER, operator, tenant, Ids.newId(),
                        DefaultRoles.OPERADOR, permissionsOf(DefaultRoles.OPERADOR), CreditorScope.ALL)))))
                .andExpect(status().isForbidden());
    }
}
