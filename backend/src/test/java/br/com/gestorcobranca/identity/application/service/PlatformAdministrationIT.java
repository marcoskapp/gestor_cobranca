package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.ClientInfo;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.LoginCommand;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.LoginResult;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.LoginStatus;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.AdminInvitation;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.ProvisionTenantCommand;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.ProvisionedTenant;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.identity.domain.model.Role;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.repository.RoleRepository;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Administração da plataforma na ativação do ambiente (decisões 520–524): bootstrap idempotente do
 * primeiro administrador, MFA obrigatório do {@code PLATFORM_ADMIN} com cadastro no primeiro login,
 * reenvio do convite do administrador inicial de uma assessoria pela plataforma e renovação do convite
 * expirado pelo "Esqueci minha senha", sem enumeração.
 * <p>
 * Nenhum outro teste cria usuários no tenant da plataforma; a ordem garante que o bootstrap rode antes
 * dos demais cenários desta classe que criam usuários nele.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformAdministrationIT extends IntegrationTest {

    private static final String PLATFORM_CNPJ = "11.444.777/0001-61";
    private static final String PASSWORD = "Senha-Forte-2026!x";
    private static final ClientInfo CLIENT = new ClientInfo("203.0.113.20", "teste");

    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    TenantAdministration tenantAdministration;
    @Autowired
    TenantProvisioning provisioning;
    @Autowired
    AuthenticationUseCases authentication;
    @Autowired
    RoleRepository roles;
    @Autowired
    UserRepository users;
    @Autowired
    CredentialService credentialService;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    JdbcTemplate jdbcTemplate;

    TenantView platform;

    @BeforeEach
    void platformTenant() {
        platform = tenantExecution.callInTenant(Ids.newId(), "test.setup",
                () -> tenantAdministration.ensurePlatformTenant(PLATFORM_CNPJ).tenant());
    }

    // ------------------------------------------------------------------ 520 bootstrap

    @Test
    @Order(1)
    void bootstrapCreatesOnlyTheFirstAdministratorAndNeverRecreatesOrOverwrites() {
        long before = platformAdmins();
        String email = "primeiro-" + suffix() + "@plataforma.test";

        bootstrap(email, "Primeira Administradora", PLATFORM_CNPJ).run(null);

        if (before == 0) {
            User admin = in(platform.id(), () -> users.findByEmail(email).orElseThrow());
            assertThat(admin.getName()).isEqualTo("Primeira Administradora");
            assertThat(admin.getStatus().name()).isEqualTo("PENDING_ACTIVATION");
            assertThat(usableActivationTokens(platform.id(), admin.getId())).isEqualTo(1);
        }
        long afterFirst = platformAdmins();
        assertThat(afterFirst).isGreaterThanOrEqualTo(1);
        List<Map<String, Object>> snapshot = platformUsersSnapshot();
        long tokens = platformTokens();

        // reinício com o mesmo e-mail e outro nome, troca do e-mail e outro CNPJ: nada muda
        bootstrap(email, "Outro Nome", PLATFORM_CNPJ).run(null);
        String other = "segundo-" + suffix() + "@plataforma.test";
        bootstrap(other, "Segundo", "45.997.418/0001-53").run(null);

        assertThat(platformAdmins()).isEqualTo(afterFirst);
        assertThat(platformUsersSnapshot()).isEqualTo(snapshot);
        assertThat(platformTokens()).isEqualTo(tokens);
        assertThat(in(platform.id(), () -> users.existsByEmail(other))).isFalse();
        assertThat(tenantExecution.callInTenant(Ids.newId(), "test", () -> tenantAdministration.findById(platform.id())
                .orElseThrow().cnpj())).isEqualTo(platform.cnpj());
    }

    @Test
    @Order(2)
    void bootstrapRefusesToStartWithoutTheCnpjOrWithABlankEmail() {
        assertThatThrownBy(() -> bootstrap("x@plataforma.test", "", " "))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("platform-cnpj");
        assertThatThrownBy(() -> bootstrap(" ", "", PLATFORM_CNPJ))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("platform-admin-email");
    }

    // ------------------------------------------------------------------ 521 MFA do PLATFORM_ADMIN

    @Test
    @Order(3)
    void platformAdministratorMustEnrollMfaOnTheFirstLogin() {
        String email = "mfa-" + suffix() + "@plataforma.test";
        in(platform.id(), () -> {
            Role role = roles.findByCode(DefaultRoles.PLATFORM_ADMIN).orElseThrow();
            User user = new User("Plataforma MFA", email, null);
            user.assignRoles(Set.of(role.getId()));
            user.defineCreditorScope(true, Set.of());
            user.definePassword(passwordEncoder.encode(PASSWORD), Instant.now());
            return users.save(user);
        });

        LoginResult login = authentication.login(new LoginCommand(platform.slug(), email, PASSWORD, CLIENT));

        assertThat(login.status()).isEqualTo(LoginStatus.MFA_ENROLLMENT_REQUIRED);
        assertThat(login.accessToken()).isNull();
        assertThat(authentication.startMfaEnrollment(login.stepToken()).otpauthUri()).startsWith("otpauth://totp/");
    }

    // ------------------------------------------------------------------ 523 reenvio pela plataforma

    @Test
    @Order(4)
    void platformResendsTheInitialAdministratorInvitationOnlyWhileTheAgencyHasNoActiveAdministrator() {
        Actor platformAdmin = platformActor(EnumSet.copyOf(DefaultRoles.platformAdmin().permissions()));
        ProvisionedTenant agency = provisionAgency("convite", platformAdmin);
        UUID tenant = agency.tenant().id();
        UUID admin = agency.adminUserId();
        expireActivationTokens(tenant, admin);
        assertThat(usableActivationTokens(tenant, admin)).isZero();

        AdminInvitation invitation = as(platformAdmin, () -> provisioning.resendAdminInvitation(tenant, "link expirou"));

        assertThat(invitation.adminUserId()).isEqualTo(admin);
        assertThat(invitation.maskedEmail()).contains("***@").doesNotContain("admin@");
        assertThat(invitation.expiresAt()).isBetween(Instant.now().plus(71, ChronoUnit.HOURS),
                Instant.now().plus(73, ChronoUnit.HOURS));
        assertThat(usableActivationTokens(tenant, admin)).isEqualTo(1);

        // reenvio de novo: o link anterior deixa de valer (sempre um único link utilizável)
        as(platformAdmin, () -> provisioning.resendAdminInvitation(tenant, "não chegou"));
        assertThat(usableActivationTokens(tenant, admin)).isEqualTo(1);

        // auditoria nas duas trilhas: a da plataforma (quem e por quê) e a da assessoria (intervenção visível)
        assertThat(audits(platform.id(), "tenant.admin_invitation_resent", tenant)).isEqualTo(2);
        assertThat(audits(tenant, "user.admin_invitation_resent", admin)).isEqualTo(2);
        assertThat(tenantExecution.callInTenant(tenant, "test", () -> jdbcTemplate.queryForObject("""
                select metadata->>'requestedByPlatformUserId' from shared.audit_event
                 where tenant_id = ? and action = 'user.admin_invitation_resent' order by seq desc limit 1""",
                String.class, tenant))).isEqualTo(platformAdmin.id().toString());

        // sem TENANT_MANAGE: negado
        Actor withoutPermission = platformActor(EnumSet.of(Permission.USER_MANAGE, Permission.USER_VIEW));
        assertThatThrownBy(() -> as(withoutPermission, () -> provisioning.resendAdminInvitation(tenant, "x")))
                .isInstanceOf(ScopeAccessDeniedException.class);

        // o próprio tenant da plataforma: pela tela de usuários ou pelo "Esqueci minha senha"
        assertThatThrownBy(() -> as(platformAdmin, () -> provisioning.resendAdminInvitation(platform.id(), "x")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code()).isEqualTo("tenant.platform_invitation"));

        // assessoria suspensa: recusa
        as(platformAdmin, () -> tenantAdministration.changeTenantStatus(tenant, false, "teste"));
        assertThatThrownBy(() -> as(platformAdmin, () -> provisioning.resendAdminInvitation(tenant, "x")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code()).isEqualTo("tenant.inactive"));
        as(platformAdmin, () -> tenantAdministration.changeTenantStatus(tenant, true, "teste"));

        // administrador ativado: a assessoria passa a administrar os próprios usuários (segregação)
        in(tenant, () -> {
            User user = users.findById(admin).orElseThrow();
            credentialService.changePassword(user, PASSWORD);
            return null;
        });
        assertThatThrownBy(() -> as(platformAdmin, () -> provisioning.resendAdminInvitation(tenant, "x")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).code()).isEqualTo("tenant.admin_already_active"));
    }

    // ------------------------------------------------------------------ 524 "Esqueci minha senha"

    @Test
    @Order(5)
    void forgotPasswordRenewsAnExpiredInvitationWithoutRevealingWhetherTheUserExists() {
        Actor platformAdmin = platformActor(EnumSet.copyOf(DefaultRoles.platformAdmin().permissions()));
        ProvisionedTenant agency = provisionAgency("esqueci", platformAdmin);
        UUID tenant = agency.tenant().id();
        UUID admin = agency.adminUserId();
        String email = in(tenant, () -> users.findById(admin).orElseThrow().getEmail());
        expireActivationTokens(tenant, admin);

        authentication.requestPasswordReset(agency.tenant().slug(), email.toUpperCase());

        assertThat(usableActivationTokens(tenant, admin)).isEqualTo(1);
        assertThat(usableTokens(tenant, admin, "PASSWORD_RESET")).isZero();
        assertThat(tenantExecution.callInTenant(tenant, "test", () -> jdbcTemplate.queryForObject("""
                select details->>'purpose' from shared.access_log
                 where tenant_id = ? and event_type = 'PASSWORD_RESET_REQUESTED' and actor_id = ?
                 order by occurred_at desc limit 1""", String.class, tenant, admin))).isEqualTo("ACTIVATION");

        // e-mail desconhecido e usuário desativado: a chamada conclui igual, sem emitir nada
        long before = tenantExecution.callInTenant(tenant, "test", () -> jdbcTemplate.queryForObject(
                "select count(*) from identity.action_token where tenant_id = ?", Long.class, tenant));
        authentication.requestPasswordReset(agency.tenant().slug(), "ninguem-" + suffix() + "@teste.com");
        in(tenant, () -> {
            User user = users.findById(admin).orElseThrow();
            user.disable(Instant.now());
            return users.save(user);
        });
        authentication.requestPasswordReset(agency.tenant().slug(), email);
        assertThat(tenantExecution.callInTenant(tenant, "test", () -> jdbcTemplate.queryForObject(
                "select count(*) from identity.action_token where tenant_id = ?", Long.class, tenant))).isEqualTo(before);
    }

    // ------------------------------------------------------------------ apoio

    private PlatformBootstrap bootstrap(String email, String name, String cnpj) {
        return new PlatformBootstrap(tenantAdministration, tenantExecution, roles, users, credentialService, email, name,
                cnpj);
    }

    private ProvisionedTenant provisionAgency(String prefix, Actor platformAdmin) {
        String slug = prefix + "-" + suffix();
        return as(platformAdmin, () -> provisioning.provision(new ProvisionTenantCommand(slug, "Assessoria " + prefix,
                prefix, "11.222.333/0001-81", "Admin " + prefix, "admin@" + slug + ".test")));
    }

    private Actor platformActor(Set<Permission> permissions) {
        return new Actor(Actor.ActorType.USER, Ids.newId(), platform.id(), Ids.newId(), "plataforma", permissions,
                CreditorScope.ALL);
    }

    private long platformAdmins() {
        return in(platform.id(), () -> roles.findByCode(DefaultRoles.PLATFORM_ADMIN)
                .map(role -> users.countWithRole(role.getId())).orElse(0L));
    }

    private List<Map<String, Object>> platformUsersSnapshot() {
        return tenantExecution.callInTenant(platform.id(), "test", () -> jdbcTemplate.queryForList("""
                select id, email, name, status, version from identity.app_user where tenant_id = ? order by id""",
                platform.id()));
    }

    private long platformTokens() {
        return tenantExecution.callInTenant(platform.id(), "test", () -> jdbcTemplate.queryForObject(
                "select count(*) from identity.action_token where tenant_id = ?", Long.class, platform.id()));
    }

    private long usableActivationTokens(UUID tenant, UUID userId) {
        return usableTokens(tenant, userId, "ACTIVATION");
    }

    private long usableTokens(UUID tenant, UUID userId, String purpose) {
        return tenantExecution.callInTenant(tenant, "test", () -> jdbcTemplate.queryForObject("""
                select count(*) from identity.action_token
                 where user_id = ? and purpose = ? and used_at is null and not invalidated and expires_at > now()""",
                Long.class, userId, purpose));
    }

    private void expireActivationTokens(UUID tenant, UUID userId) {
        tenantExecution.callInTenant(tenant, "test", () -> jdbcTemplate.update("""
                update identity.action_token set expires_at = now() - interval '1 hour'
                 where user_id = ? and purpose = 'ACTIVATION'""", userId));
    }

    private long audits(UUID tenant, String action, UUID entityId) {
        return tenantExecution.callInTenant(tenant, "test", () -> jdbcTemplate.queryForObject(
                "select count(*) from shared.audit_event where tenant_id = ? and action = ? and entity_id = ?",
                Long.class, tenant, action, entityId));
    }

    private <T> T in(UUID tenant, Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    private <T> T as(Actor actor, Supplier<T> action) {
        return tenantExecution.callAs(actor, action);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
