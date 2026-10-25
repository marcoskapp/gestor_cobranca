package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.ClientInfo;
import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases.RoleCommand;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases.CreateUserCommand;
import br.com.gestorcobranca.identity.application.port.in.UserManagementUseCases.UserView;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.identity.domain.model.Role;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.repository.RoleRepository;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration.CreateTenantCommand;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hierarquia de administração (revisão de segurança A3, A4, A8): quem gerencia perfis e usuários só
 * administra quem domina (permissões e escopo de credores), o perfil ADMIN é protegido, a redução de
 * permissões encerra as sessões na hora, o MFA de administrador exige outro administrador e códigos de
 * perfis padrão são reservados.
 */
class AdministrationHierarchyIT extends IntegrationTest {

    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    TenantAdministration tenantAdministration;
    @Autowired
    CreditorUseCases creditors;
    @Autowired
    DefaultRolesSynchronizer synchronizer;
    @Autowired
    RoleRepository roles;
    @Autowired
    UserRepository users;
    @Autowired
    RoleManagementUseCases roleManagement;
    @Autowired
    UserManagementUseCases userManagement;
    @Autowired
    SessionManager sessionManager;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    MeterRegistry meterRegistry;

    UUID tenant;
    UUID creditorA;
    UUID creditorB;
    Role adminRole;
    Role operatorRole;
    UUID admin1;
    UUID admin2;
    UUID operatorA;
    UUID operatorB;

    @BeforeEach
    void setUp() {
        String slug = "hier-" + UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new CreateTenantCommand(slug, "Hier Ltda", "Hier", "11.444.777/0001-61")).id());
        synchronizer.syncTenant(tenant);
        creditorA = system(() -> creditors.create(new CreditorCommand("Banco A S.A.", "Banco A", "11.222.333/0001-81", null, null)).id());
        creditorB = system(() -> creditors.create(new CreditorCommand("Loja B S.A.", "Loja B", "45.997.418/0001-53", null, null)).id());
        adminRole = system(() -> roles.findByCode(DefaultRoles.ADMIN).orElseThrow());
        operatorRole = system(() -> roles.findByCode(DefaultRoles.OPERADOR).orElseThrow());
        admin1 = user("admin1", adminRole.getId(), true, Set.of());
        admin2 = user("admin2", adminRole.getId(), true, Set.of());
        operatorA = user("opa", operatorRole.getId(), false, Set.of(creditorA));
        operatorB = user("opb", operatorRole.getId(), false, Set.of(creditorB));
    }

    // ------------------------------------------------------------------ A3 perfis

    @Test
    void roleManagerCannotRewriteARoleItDoesNotDominateNorTheAdminRole() {
        Actor rh = actor(Ids.newId(), EnumSet.of(Permission.ROLE_MANAGE, Permission.USER_VIEW), CreditorScope.ALL);

        assertThatThrownBy(() -> as(rh, () -> roleManagement.update(adminRole.getId(),
                new RoleCommand(null, "Administrador", null, Set.of("USER_VIEW")))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> as(rh, () -> roleManagement.changeStatus(operatorRole.getId(), false)))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThat(system(() -> roles.findById(adminRole.getId()).orElseThrow().getPermissions()))
                .isEqualTo(adminRole.getPermissions());

        // perfil que ele domina: pode editar dentro do que tem
        UUID mine = as(rh, () -> roleManagement.create(new RoleCommand("RECEPCAO", "Recepção", null, Set.of("USER_VIEW"))).id());
        assertThat(as(rh, () -> roleManagement.update(mine, new RoleCommand(null, "Recepção 2", null,
                Set.of("USER_VIEW", "ROLE_MANAGE")))).permissions()).containsExactlyInAnyOrder("USER_VIEW", "ROLE_MANAGE");
    }

    @Test
    void adminRoleKeepsGovernanceAndOnlyAnAdministratorDropsDefaultPermissions() {
        Set<String> withoutRecordings = names(adminRole.getPermissions());
        withoutRecordings.remove(Permission.CALL_RECORDING_LISTEN.name());

        // todas as permissões, mas sem o perfil ADMIN: não reduz o ADMIN abaixo do padrão
        UUID roleAll = system(() -> roles.save(new Role("TUDO", "Tudo", null, adminRole.getPermissions(), false)).getId());
        UUID almostAdmin = user("quase", roleAll, true, Set.of());
        Actor notAdmin = actor(almostAdmin, adminRole.getPermissions(), CreditorScope.ALL);
        assertThatThrownBy(() -> as(notAdmin, () -> roleManagement.update(adminRole.getId(),
                new RoleCommand(null, "Administrador", null, withoutRecordings))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("administrador");

        Actor admin = actor(admin1, adminRole.getPermissions(), CreditorScope.ALL);
        assertThat(as(admin, () -> roleManagement.update(adminRole.getId(),
                new RoleCommand(null, "Administrador", null, withoutRecordings))).permissions())
                .doesNotContain(Permission.CALL_RECORDING_LISTEN.name());

        Set<String> withoutGovernance = new HashSet<>(withoutRecordings);
        withoutGovernance.remove(Permission.ROLE_MANAGE.name());
        assertThatThrownBy(() -> as(admin, () -> roleManagement.update(adminRole.getId(),
                new RoleCommand(null, "Administrador", null, withoutGovernance))))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code()).isEqualTo("role.admin_governance_required"));
    }

    @Test
    void reducingARoleRevokesTheSessionsOfItsHoldersImmediately() {
        openSession(operatorA);
        openSession(operatorB);
        assertThat(activeSessions(operatorA)).isEqualTo(1);
        Actor admin = actor(admin1, adminRole.getPermissions(), CreditorScope.ALL);

        // acrescentar permissão não derruba ninguém
        Set<String> more = names(operatorRole.getPermissions());
        more.add(Permission.REPORT_VIEW.name());
        as(admin, () -> roleManagement.update(operatorRole.getId(), new RoleCommand(null, "Operador", null, more)));
        assertThat(activeSessions(operatorA)).isEqualTo(1);

        Set<String> fewer = new HashSet<>(more);
        fewer.remove(Permission.REPORT_VIEW.name());
        fewer.remove(Permission.CASE_VIEW.name());
        as(admin, () -> roleManagement.update(operatorRole.getId(), new RoleCommand(null, "Operador", null, fewer)));
        assertThat(activeSessions(operatorA)).isZero();
        assertThat(activeSessions(operatorB)).isZero();
    }

    // ------------------------------------------------------------------ A8 códigos reservados

    @Test
    void defaultAndPlatformRoleCodesAreReserved() {
        Actor admin = actor(admin1, adminRole.getPermissions(), CreditorScope.ALL);
        for (String code : Set.of(DefaultRoles.PLATFORM_ADMIN, DefaultRoles.ADMIN, DefaultRoles.OPERADOR)) {
            assertThatThrownBy(() -> as(admin, () -> roleManagement.create(new RoleCommand(code, "X", null, Set.of("USER_VIEW")))))
                    .isInstanceOf(BusinessRuleException.class)
                    .satisfies(e -> assertThat(((BusinessRuleException) e).code()).isEqualTo("role.code_reserved"));
        }
    }

    // ------------------------------------------------------------------ A4 usuários

    @Test
    void userManagerOnlyManagesUsersItDominatesInPermissionsAndCreditorScope() {
        // "RH" do credor A: permissões do operador + gestão de usuários
        Set<Permission> rhPermissions = EnumSet.copyOf(operatorRole.getPermissions());
        rhPermissions.addAll(EnumSet.of(Permission.USER_MANAGE, Permission.USER_VIEW));
        UUID rhRole = system(() -> roles.save(new Role("RH_A", "RH A", null, rhPermissions, false)).getId());
        UUID rhUser = user("rh", rhRole, false, Set.of(creditorA));
        Actor rh = actor(rhUser, rhPermissions, CreditorScope.of(Set.of(creditorA)));

        // superior (administrador): nada
        assertThatThrownBy(() -> as(rh, () -> userManagement.resetMfa(admin1, "teste")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> as(rh, () -> userManagement.disable(admin1, "teste")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> as(rh, () -> userManagement.assignRoles(admin1, Set.of(operatorRole.getId()))))
                .isInstanceOf(ResourceNotFoundException.class);
        // operador de outro credor: fora do escopo
        assertThatThrownBy(() -> as(rh, () -> userManagement.unlock(operatorB))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> as(rh, () -> userManagement.get(operatorB))).isInstanceOf(ResourceNotFoundException.class);
        // usuário com o mesmo escopo e mais permissões: 403
        UUID financeiro = system(() -> roles.findByCode(DefaultRoles.FINANCEIRO).orElseThrow().getId());
        UUID financeiroA = user("fin", financeiro, false, Set.of(creditorA));
        assertThatThrownBy(() -> as(rh, () -> userManagement.disable(financeiroA, "teste")))
                .isInstanceOf(ScopeAccessDeniedException.class);

        // quem ele domina: pode administrar
        assertThat(as(rh, () -> userManagement.disable(operatorA, "teste")).status()).isEqualTo("DISABLED");
        assertThat(as(rh, () -> userManagement.enable(operatorA)).status()).isNotEqualTo("DISABLED");

        // listagem: só usuários com escopo contido no dele
        Set<UUID> listed = as(rh, () -> userManagement.search(null, null, null, null, PageQuery.of(0, 50)))
                .content().stream().map(UserView::id).collect(Collectors.toSet());
        assertThat(listed).contains(operatorA, rhUser, financeiroA).doesNotContain(admin1, admin2, operatorB);
        Set<UUID> listedByAdmin = as(actor(admin1, adminRole.getPermissions(), CreditorScope.ALL),
                () -> userManagement.search(null, null, null, null, PageQuery.of(0, 50)))
                .content().stream().map(UserView::id).collect(Collectors.toSet());
        assertThat(listedByAdmin).contains(admin1, admin2, operatorA, operatorB, rhUser);
    }

    @Test
    void adminMfaResetRequiresAnotherAdministratorAndAlertsTheTarget() throws InterruptedException {
        // todas as permissões e todos os credores, mas sem o perfil ADMIN
        UUID roleAll = system(() -> roles.save(new Role("TUDO", "Tudo", null, adminRole.getPermissions(), false)).getId());
        UUID almostAdmin = user("quase", roleAll, true, Set.of());
        assertThatThrownBy(() -> as(actor(almostAdmin, adminRole.getPermissions(), CreditorScope.ALL),
                () -> userManagement.resetMfa(admin2, "perdeu o celular")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code()).isEqualTo("user.admin_mfa_reset_requires_admin"));
        // nem o próprio administrador
        Actor admin = actor(admin1, adminRole.getPermissions(), CreditorScope.ALL);
        assertThatThrownBy(() -> as(admin, () -> userManagement.resetMfa(admin1, "eu mesmo")))
                .isInstanceOf(BusinessRuleException.class);

        double alertsBefore = alerts();
        openSession(admin2);
        as(admin, () -> userManagement.resetMfa(admin2, "perdeu o celular"));
        assertThat(activeSessions(admin2)).isZero();
        long deadline = System.currentTimeMillis() + 10_000;
        while (alerts() <= alertsBefore && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(alerts()).isGreaterThan(alertsBefore);
        assertThat(system(() -> jdbcTemplate.queryForObject("""
                select count(*) from shared.access_log where event_type = 'MFA_RESET' and details->>'targetUserId' = ?
                   and details->>'targetIsAdmin' = 'true'""", Long.class, admin2.toString()))).isEqualTo(1L);
    }

    // ------------------------------------------------------------------ apoio

    private double alerts() {
        return meterRegistry.find("mail.sent").tag("type", "security_alert").counters().stream()
                .mapToDouble(c -> c.count()).sum();
    }

    private UUID user(String key, UUID roleId, boolean allCreditors, Set<UUID> creditorIds) {
        UUID id = system(() -> userManagement.create(new CreateUserCommand(key, key + "-"
                + UUID.randomUUID().toString().substring(0, 6) + "@teste.com", null, Set.of(roleId), allCreditors,
                creditorIds)).id());
        system(() -> jdbcTemplate.update("update identity.app_user set status = 'ACTIVE' where id = ?", id));
        return id;
    }

    private void openSession(UUID userId) {
        system(() -> {
            User u = users.findById(userId).orElseThrow();
            return sessionManager.open(u, tenantAdministration.findById(tenant).orElseThrow(), true,
                    new ClientInfo("203.0.113.9", "teste"));
        });
    }

    private long activeSessions(UUID userId) {
        return system(() -> jdbcTemplate.queryForObject(
                "select count(*) from identity.auth_session where user_id = ? and status = 'ACTIVE'", Long.class, userId));
    }

    private Actor actor(UUID userId, Set<Permission> permissions, CreditorScope scope) {
        return new Actor(Actor.ActorType.USER, userId, tenant, Ids.newId(), "teste", permissions, scope);
    }

    private <T> T as(Actor actor, Supplier<T> action) {
        return tenantExecution.callAs(actor, action);
    }

    private <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    private static Set<String> names(Set<Permission> permissions) {
        return permissions.stream().map(Enum::name).collect(Collectors.toCollection(HashSet::new));
    }
}
