package br.com.gestorcobranca.identity;

import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases.PermissionView;
import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases.RoleCommand;
import br.com.gestorcobranca.identity.application.service.DefaultRolesSynchronizer;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.identity.domain.model.Role;
import br.com.gestorcobranca.identity.domain.repository.RoleRepository;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration.CreateTenantCommand;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Perfis padrão por assessoria: criação dos perfis de cada tipo de usuário, chegada de permissões
 * novas aos perfis existentes sem desfazer o que o administrador retirou, e criação de perfis pelo
 * administrador (só se concede o que se tem; a tela sabe o que pode ser concedido).
 */
class DefaultRolesIT extends IntegrationTest {

    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    TenantAdministration tenantAdministration;
    @Autowired
    RoleRepository roles;
    @Autowired
    DefaultRolesSynchronizer synchronizer;
    @Autowired
    RoleManagementUseCases roleManagement;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void everyTenantGetsOneDefaultRolePerUserTypeAndTheAdminRoleHasEverything() {
        UUID tenant = createTenant("perfis");

        DefaultRolesSynchronizer.SyncResult result = synchronizer.syncTenant(tenant);

        List<String> expected = DefaultRoles.forTenant().stream().map(DefaultRoles.Definition::code).toList();
        assertThat(result.createdRoles()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(expected).contains(DefaultRoles.ADMIN, DefaultRoles.GESTOR, DefaultRoles.SUPERVISOR,
                DefaultRoles.OPERADOR, DefaultRoles.FINANCEIRO, DefaultRoles.AUDITOR, DefaultRoles.JURIDICO,
                DefaultRoles.ADVOGADO_PARCEIRO, DefaultRoles.ANALISTA_CARTEIRA, DefaultRoles.ESTRATEGIA,
                DefaultRoles.QUALIDADE, DefaultRoles.COMPLIANCE, DefaultRoles.INTEGRACAO);
        Role admin = in(tenant, () -> roles.findByCode(DefaultRoles.ADMIN).orElseThrow());
        Set<Permission> all = EnumSet.allOf(Permission.class);
        all.remove(Permission.TENANT_MANAGE);
        assertThat(admin.getPermissions()).isEqualTo(all);
        assertThat(admin.isSystemRole()).isTrue();

        // segunda execução não muda nada
        DefaultRolesSynchronizer.SyncResult again = synchronizer.syncTenant(tenant);
        assertThat(again.createdRoles()).isEmpty();
        assertThat(again.updatedRoles()).isEmpty();
    }

    @Test
    void permissionsAddedByNewVersionsReachExistingRolesButAdminRemovalsAreKept() {
        UUID tenant = createTenant("drift");
        synchronizer.syncTenant(tenant);
        UUID adminRole = in(tenant, () -> roles.findByCode(DefaultRoles.ADMIN).orElseThrow().getId());

        // assessoria provisionada antes de IMPORT_APPROVE existir: nem concedida nem aplicada
        in(tenant, () -> jdbcTemplate.update("""
                delete from identity.role_permission where role_id = ? and permission = 'IMPORT_APPROVE'""", adminRole));
        in(tenant, () -> jdbcTemplate.update("""
                delete from identity.role_default_baseline where role_id = ? and permission = 'IMPORT_APPROVE'""",
                adminRole));
        DefaultRolesSynchronizer.SyncResult result = synchronizer.syncTenant(tenant);
        assertThat(result.updatedRoles()).containsExactly(DefaultRoles.ADMIN);
        assertThat(in(tenant, () -> roles.findById(adminRole).orElseThrow().getPermissions()))
                .contains(Permission.IMPORT_APPROVE);
        assertThat(in(tenant, () -> jdbcTemplate.queryForObject("""
                select count(*) from shared.audit_event where action = 'role.default_permissions_added'
                   and entity_id = ?""", Long.class, adminRole))).isEqualTo(1L);

        // o administrador retira uma permissão de um perfil de sistema: ela não volta
        in(tenant, () -> {
            Role supervisor = roles.findByCode(DefaultRoles.SUPERVISOR).orElseThrow();
            Set<Permission> fewer = EnumSet.copyOf(supervisor.getPermissions());
            fewer.remove(Permission.CALL_RECORDING_LISTEN);
            supervisor.update(supervisor.getName(), supervisor.getDescription(), fewer);
            return roles.save(supervisor);
        });
        synchronizer.syncTenant(tenant);
        assertThat(in(tenant, () -> roles.findByCode(DefaultRoles.SUPERVISOR).orElseThrow().getPermissions()))
                .doesNotContain(Permission.CALL_RECORDING_LISTEN);
    }

    @Test
    void rolesCreatedByTheTenantAndThePlatformTenantAreNotTouched() {
        UUID tenant = createTenant("custom");
        in(tenant, () -> roles.save(new Role("COBRANCA_NOTURNA", "Cobrança noturna", null,
                Set.of(Permission.CASE_VIEW), false)));
        synchronizer.syncTenant(tenant);
        assertThat(in(tenant, () -> roles.findByCode("COBRANCA_NOTURNA").orElseThrow().getPermissions()))
                .containsExactly(Permission.CASE_VIEW);

        UUID platform = createTenant("plat");
        in(platform, () -> roles.save(new Role(DefaultRoles.PLATFORM_ADMIN, "Plataforma", null,
                DefaultRoles.platformAdmin().permissions(), true)));
        assertThat(synchronizer.syncTenant(platform).createdRoles()).isEmpty();
        assertThat(in(platform, () -> roles.findAll()).stream().map(Role::getCode)).containsExactly(DefaultRoles.PLATFORM_ADMIN);
    }

    @Test
    void adminCreatesAFullAccessRoleAndALimitedUserIsToldWhatCannotBeGranted() {
        UUID tenant = createTenant("cria");
        synchronizer.syncTenant(tenant);
        Set<Permission> adminPermissions = in(tenant, () -> roles.findByCode(DefaultRoles.ADMIN).orElseThrow().getPermissions());

        List<PermissionView> catalog = as(tenant, adminPermissions, roleManagement::permissionCatalog);
        assertThat(catalog).allMatch(PermissionView::grantable);
        Set<String> everything = catalog.stream().map(PermissionView::code).collect(Collectors.toSet());
        assertThat(as(tenant, adminPermissions, () -> roleManagement.create(
                new RoleCommand("ACESSO_TOTAL", "Acesso total", null, everything))).permissions())
                .containsExactlyInAnyOrderElementsOf(everything);

        // quem gerencia perfis com poucas permissões: a tela sabe o que pode conceder e o erro explica
        Set<Permission> limited = EnumSet.of(Permission.ROLE_MANAGE, Permission.USER_VIEW, Permission.CASE_VIEW);
        List<PermissionView> limitedCatalog = as(tenant, limited, roleManagement::permissionCatalog);
        assertThat(limitedCatalog.stream().filter(PermissionView::grantable).map(PermissionView::code))
                .containsExactlyInAnyOrder("ROLE_MANAGE", "USER_VIEW", "CASE_VIEW");
        assertThatThrownBy(() -> as(tenant, limited, () -> roleManagement.create(
                new RoleCommand("OUTRO", "Outro", null, Set.of("CASE_VIEW", "PAYMENT_REVERSE", "USER_MANAGE")))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("PAYMENT_REVERSE")
                .hasMessageContaining("USER_MANAGE");
    }

    private UUID createTenant(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new CreateTenantCommand(slug, prefix + " Ltda", prefix, "11.444.777/0001-61")).id());
    }

    private <T> T in(UUID tenantId, Supplier<T> action) {
        return tenantExecution.callInTenant(tenantId, "test", action);
    }

    private <T> T as(UUID tenantId, Set<Permission> permissions, Supplier<T> action) {
        Actor actor = new Actor(Actor.ActorType.USER, Ids.newId(), tenantId, Ids.newId(), "gestor",
                Set.copyOf(permissions), CreditorScope.ALL);
        return tenantExecution.callAs(actor, action);
    }

    @SuppressWarnings("unused")
    private static Set<String> names(Permission... permissions) {
        return Arrays.stream(permissions).map(Enum::name).collect(Collectors.toSet());
    }
}
