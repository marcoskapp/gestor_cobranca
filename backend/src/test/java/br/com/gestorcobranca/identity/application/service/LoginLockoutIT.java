package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.ClientInfo;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.LoginCommand;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.LoginResult;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.LoginStatus;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.repository.RoleRepository;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import br.com.gestorcobranca.identity.domain.service.Totp;
import br.com.gestorcobranca.identity.domain.service.TotpTestCodes;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration.CreateTenantCommand;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.AuthenticationFailedException;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bloqueio progressivo com contagem única para senha e segundo fator: acertar a senha não zera as falhas
 * de MFA anteriores (senão bastaria refazer o login a cada 4 códigos errados para tentar códigos sem limite);
 * o contador só zera quando a autenticação termina.
 */
class LoginLockoutIT extends IntegrationTest {

    private static final String PASSWORD = "Senha-Forte-2026!x";
    private static final ClientInfo CLIENT = new ClientInfo("203.0.113.40", "teste");
    /** Nunca é um código TOTP válido (não numérico). */
    private static final String WRONG_CODE = "12a456";

    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    TenantAdministration tenantAdministration;
    @Autowired
    DefaultRolesSynchronizer synchronizer;
    @Autowired
    AuthenticationUseCases authentication;
    @Autowired
    RoleRepository roles;
    @Autowired
    UserRepository users;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    JdbcTemplate jdbcTemplate;

    UUID tenant;
    String slug;

    @BeforeEach
    void setUp() {
        slug = "bloqueio-" + UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new CreateTenantCommand(slug, "Bloqueio Ltda", "Bloqueio", "11.444.777/0001-61")).id());
        synchronizer.syncTenant(tenant);
    }

    @Test
    void passwordLoginDoesNotResetMfaFailuresSoTheFifthConsecutiveFailureLocks() {
        String secret = Totp.generateSecret();
        UUID userId = mfaUser("mfa@bloqueio.test", secret);

        LoginResult first = login("mfa@bloqueio.test");
        assertThat(first.status()).isEqualTo(LoginStatus.MFA_REQUIRED);
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> authentication.verifyMfa(first.stepToken(), WRONG_CODE, CLIENT))
                    .isInstanceOf(AuthenticationFailedException.class);
        }
        assertThat(user(userId).getFailedAttempts()).isEqualTo(4);

        // a senha confere de novo: o contador continua em 4
        LoginResult second = login("mfa@bloqueio.test");
        assertThat(second.status()).isEqualTo(LoginStatus.MFA_REQUIRED);
        assertThat(user(userId).getFailedAttempts()).isEqualTo(4);

        assertThatThrownBy(() -> authentication.verifyMfa(second.stepToken(), WRONG_CODE, CLIENT))
                .isInstanceOf(AuthenticationFailedException.class);
        User locked = user(userId);
        assertThat(locked.isLocked(Instant.now())).isTrue();
        assertThat(blockedEvents(userId)).isEqualTo(1);

        // bloqueada: nem a senha certa passa
        assertThatThrownBy(() -> login("mfa@bloqueio.test")).isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void completedAuthenticationResetsTheCounter() {
        String secret = Totp.generateSecret();
        UUID userId = mfaUser("ok@bloqueio.test", secret);

        LoginResult step = login("ok@bloqueio.test");
        assertThatThrownBy(() -> authentication.verifyMfa(step.stepToken(), WRONG_CODE, CLIENT))
                .isInstanceOf(AuthenticationFailedException.class);
        assertThat(user(userId).getFailedAttempts()).isEqualTo(1);

        String code = TotpTestCodes.current(secret, Instant.now());
        LoginResult done = authentication.verifyMfa(step.stepToken(), code, CLIENT);

        assertThat(done.status()).isEqualTo(LoginStatus.AUTHENTICATED);
        assertThat(user(userId).getFailedAttempts()).isZero();
    }

    private UUID mfaUser(String email, String secret) {
        return tenantExecution.callInTenant(tenant, "test.setup", () -> {
            User user = new User("Usuária MFA", email, null);
            user.assignRoles(Set.of(roles.findByCode(DefaultRoles.OPERADOR).orElseThrow().getId()));
            user.defineCreditorScope(true, Set.of());
            user.definePassword(passwordEncoder.encode(PASSWORD), Instant.now());
            user.startMfaEnrollment(secret);
            // passo antigo: o código do passo atual continua aceito no teste
            user.confirmMfaEnrollment(Instant.now(), 1);
            return users.save(user).getId();
        });
    }

    private LoginResult login(String email) {
        return authentication.login(new LoginCommand(slug, email, PASSWORD, CLIENT));
    }

    private User user(UUID id) {
        return tenantExecution.callInTenant(tenant, "test", () -> users.findById(id).orElseThrow());
    }

    private long blockedEvents(UUID userId) {
        return tenantExecution.callInTenant(tenant, "test", () -> jdbcTemplate.queryForObject("""
                select count(*) from shared.access_log
                 where tenant_id = ? and actor_id = ? and event_type = 'LOGIN_BLOCKED'""", Long.class, tenant, userId));
    }
}
