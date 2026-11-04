package br.com.gestorcobranca.identity.domain;

import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.identity.domain.model.Role;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.security.Permission;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Permissões exclusivas da plataforma: só o perfil de sistema PLATFORM_ADMIN as recebe. */
class PlatformOnlyPermissionsTest {

    @Test
    void platformBillingPermissionsArePlatformOnly() {
        assertThat(Permission.PLATFORM_ONLY).containsExactlyInAnyOrder(Permission.TENANT_MANAGE,
                Permission.PLATFORM_BILLING_VIEW, Permission.PLATFORM_BILLING_MANAGE,
                Permission.PLATFORM_API_TOKENS_MANAGE);
        assertThat(Permission.TENANT_BILLING_VIEW.isPlatformOnly()).isFalse();
        assertThat(DefaultRoles.platformAdmin().permissions()).containsAll(Permission.PLATFORM_ONLY);
        DefaultRoles.forTenant().forEach(d -> assertThat(d.permissions()).doesNotContainAnyElementsOf(Permission.PLATFORM_ONLY));
        assertThat(DefaultRoles.forTenant().stream().filter(d -> d.code().equals(DefaultRoles.ADMIN)).findFirst()
                .orElseThrow().permissions()).contains(Permission.TENANT_BILLING_VIEW);
    }

    @Test
    void tenantRolesNeverReceiveThemAndThePlatformRoleGetsThemOnce() {
        assertThatThrownBy(() -> new Role("FINANCEIRO_X", "Financeiro", null, Set.of(Permission.PLATFORM_BILLING_VIEW),
                false)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new Role("ADMIN", "Administrador", null, Set.of(Permission.PLATFORM_BILLING_MANAGE),
                true)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new Role("INTEGRACAO_X", "Integrações", null,
                Set.of(Permission.PLATFORM_API_TOKENS_MANAGE), false)).isInstanceOf(BusinessRuleException.class);
        Role admin = new Role("ADMIN", "Administrador", null, Set.of(Permission.USER_VIEW), true);
        assertThat(admin.applyDefaultPermissions(EnumSet.of(Permission.PLATFORM_BILLING_VIEW, Permission.CASE_VIEW)))
                .containsExactly(Permission.CASE_VIEW);

        // perfil da plataforma criado numa versão anterior: recebe as permissões novas uma única vez
        Role platform = new Role(DefaultRoles.PLATFORM_ADMIN, "Administrador da plataforma", null,
                EnumSet.of(Permission.TENANT_MANAGE, Permission.USER_VIEW), true);
        assertThat(platform.applyDefaultPermissions(DefaultRoles.platformAdmin().permissions()))
                .contains(Permission.PLATFORM_BILLING_VIEW, Permission.PLATFORM_BILLING_MANAGE,
                        Permission.PLATFORM_API_TOKENS_MANAGE);
        assertThat(platform.applyDefaultPermissions(DefaultRoles.platformAdmin().permissions())).isEmpty();
    }
}
