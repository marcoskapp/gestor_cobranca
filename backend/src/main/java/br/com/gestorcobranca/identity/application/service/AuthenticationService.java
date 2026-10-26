package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.IdentityProperties;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases;
import br.com.gestorcobranca.identity.application.port.out.IdentityMailer;
import br.com.gestorcobranca.identity.application.port.out.TokenIssuer;
import br.com.gestorcobranca.identity.application.port.out.TokenIssuer.StepClaims;
import br.com.gestorcobranca.identity.domain.model.ActionToken;
import br.com.gestorcobranca.identity.domain.model.AuthSession;
import br.com.gestorcobranca.identity.domain.model.RefreshToken;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.model.UserStatus;
import br.com.gestorcobranca.identity.domain.repository.CredentialRepository;
import br.com.gestorcobranca.identity.domain.repository.SessionRepository;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import br.com.gestorcobranca.identity.domain.service.OpaqueToken;
import br.com.gestorcobranca.identity.domain.service.Totp;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.logging.SensitiveDataMasker;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.AuthenticationFailedException;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.TokenClaims;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Autenticação de usuários. Cada etapa executa em transação própria no tenant do usuário e
 * devolve um resultado; falhas são convertidas em exceção somente <em>após</em> o commit, para
 * que o contador de tentativas e o bloqueio sejam sempre persistidos.
 * <p>
 * Todas as falhas retornam a mesma mensagem genérica; o motivo real fica no log de acesso.
 */
@Service
class AuthenticationService implements AuthenticationUseCases {

    /** Hash Argon2 de uma senha aleatória: usado para equalizar o tempo de resposta. */
    private final String dummyHash;

    private final TenantAdministration tenantAdministration;
    private final TenantExecution tenantExecution;
    private final UserRepository users;
    private final SessionRepository sessions;
    private final CredentialRepository credentials;
    private final SessionManager sessionManager;
    private final CredentialService credentialService;
    private final AccessProfileResolver profileResolver;
    private final TokenIssuer tokenIssuer;
    private final PasswordEncoder passwordEncoder;
    private final IdentityMailer mailer;
    private final AccessLogger accessLogger;
    private final CurrentActor currentActor;
    private final IdentityProperties properties;
    private final Duration stepTokenTtl;
    private final String baseUrl;
    private final Clock clock;

    AuthenticationService(TenantAdministration tenantAdministration, TenantExecution tenantExecution, UserRepository users,
                          SessionRepository sessions, CredentialRepository credentials, SessionManager sessionManager,
                          CredentialService credentialService, AccessProfileResolver profileResolver,
                          TokenIssuer tokenIssuer, PasswordEncoder passwordEncoder, IdentityMailer mailer,
                          AccessLogger accessLogger, CurrentActor currentActor, IdentityProperties properties,
                          br.com.gestorcobranca.shared.security.JwtProperties jwtProperties,
                          @org.springframework.beans.factory.annotation.Value("${app.base-url}") String baseUrl,
                          Clock clock) {
        this.tenantAdministration = tenantAdministration;
        this.tenantExecution = tenantExecution;
        this.users = users;
        this.sessions = sessions;
        this.credentials = credentials;
        this.sessionManager = sessionManager;
        this.credentialService = credentialService;
        this.profileResolver = profileResolver;
        this.tokenIssuer = tokenIssuer;
        this.passwordEncoder = passwordEncoder;
        this.mailer = mailer;
        this.accessLogger = accessLogger;
        this.currentActor = currentActor;
        this.properties = properties;
        this.stepTokenTtl = jwtProperties.mfaTokenTtl();
        this.baseUrl = baseUrl;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    // =================================================================== login

    @Override
    public LoginResult login(LoginCommand command) {
        String maskedEmail = SensitiveDataMasker.maskEmail(command.email());
        Optional<TenantView> tenant = tenantAdministration.findBySlug(command.tenantSlug()).filter(TenantView::active);
        if (tenant.isEmpty() || command.email() == null || command.password() == null) {
            passwordEncoder.matches(String.valueOf(command.password()), dummyHash);
            accessLogger.log(AccessEvent.anonymous(AccessEventType.LOGIN_FAILURE, tenant.map(TenantView::id).orElse(null),
                    maskedEmail, Map.of("reason", tenant.isEmpty() ? "tenant_unknown_or_inactive" : "missing_fields")));
            throw AuthenticationFailedException.invalidCredentials();
        }
        TenantView tenantView = tenant.get();
        Outcome outcome = tenantExecution.callInTenant(tenantView.id(), "identity.login",
                () -> attemptLogin(tenantView, command));
        return finish(outcome, tenantView.id(), maskedEmail);
    }

    private Outcome attemptLogin(TenantView tenant, LoginCommand command) {
        Instant now = Instant.now(clock);
        Optional<User> found = users.findByEmailForUpdate(normalizeEmailSafely(command.email()));
        if (found.isEmpty()) {
            passwordEncoder.matches(command.password(), dummyHash);
            return Outcome.failure(null, AccessEventType.LOGIN_FAILURE, "unknown_user");
        }
        User user = found.get();
        if (user.isLocked(now)) {
            passwordEncoder.matches(command.password(), dummyHash);
            return Outcome.failure(user.getId(), AccessEventType.LOGIN_BLOCKED, "account_locked");
        }
        if (user.getStatus() != UserStatus.ACTIVE || user.getPasswordHash() == null) {
            passwordEncoder.matches(command.password(), dummyHash);
            return Outcome.failure(user.getId(), AccessEventType.LOGIN_FAILURE, "user_" + user.getStatus().name().toLowerCase());
        }
        if (!passwordEncoder.matches(command.password(), user.getPasswordHash())) {
            boolean lockedNow = user.registerFailedLogin(now, properties.lockout().toPolicy());
            users.save(user);
            if (lockedNow) {
                notifyLockout(user);
                return Outcome.failure(user.getId(), AccessEventType.LOGIN_BLOCKED, "locked_after_failures");
            }
            return Outcome.failure(user.getId(), AccessEventType.LOGIN_FAILURE, "bad_password");
        }
        if (passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
            user.definePassword(passwordEncoder.encode(command.password()), user.getPasswordChangedAt());
        }
        user.unlock();
        users.save(user);
        return nextStepAfterPassword(user, tenant, command.client(), now);
    }

    private Outcome nextStepAfterPassword(User user, TenantView tenant, ClientInfo client, Instant now) {
        if (user.isMustChangePassword() || user.isPasswordExpired(now, properties.password().maxAge())) {
            String step = tokenIssuer.issueStepToken(TokenClaims.TYPE_PASSWORD_CHANGE, user.getId(), user.getTenantId(), stepTokenTtl);
            return Outcome.step(user.getId(), LoginResult.step(LoginStatus.PASSWORD_CHANGE_REQUIRED, step), "password_change_required");
        }
        if (user.isMfaEnabled()) {
            String step = tokenIssuer.issueStepToken(TokenClaims.TYPE_MFA, user.getId(), user.getTenantId(), stepTokenTtl);
            return Outcome.step(user.getId(), LoginResult.step(LoginStatus.MFA_REQUIRED, step), "mfa_required");
        }
        if (mfaRequiredFor(user)) {
            String step = tokenIssuer.issueStepToken(TokenClaims.TYPE_MFA_ENROLL, user.getId(), user.getTenantId(), stepTokenTtl);
            return Outcome.step(user.getId(), LoginResult.step(LoginStatus.MFA_ENROLLMENT_REQUIRED, step), "mfa_enrollment_required");
        }
        user.registerSuccessfulLogin(now);
        users.save(user);
        LoginResult result = sessionManager.open(user, tenant, false, client);
        return Outcome.authenticated(user.getId(), result);
    }

    private boolean mfaRequiredFor(User user) {
        return profileResolver.resolve(user).roleCodes().stream()
                .anyMatch(code -> properties.mfa().requiredForRoles().contains(code));
    }

    // =================================================================== MFA

    @Override
    public LoginResult verifyMfa(String stepToken, String code, ClientInfo client) {
        StepClaims claims = readStep(stepToken, TokenClaims.TYPE_MFA);
        TenantView tenant = activeTenant(claims.tenantId());
        Outcome outcome = tenantExecution.callInTenant(tenant.id(), "identity.mfa", () -> {
            Instant now = Instant.now(clock);
            User user = users.findById(claims.userId()).orElse(null);
            if (user == null || !user.canAuthenticate(now) || !user.isMfaEnabled()) {
                return Outcome.failure(claims.userId(), AccessEventType.MFA_FAILURE, "user_not_eligible");
            }
            OptionalLong step = Totp.verify(user.getMfaSecret(), code, now);
            if (step.isEmpty() || !user.acceptMfaStep(step.getAsLong())) {
                boolean lockedNow = user.registerFailedLogin(now, properties.lockout().toPolicy());
                users.save(user);
                if (lockedNow) {
                    notifyLockout(user);
                }
                return Outcome.failure(user.getId(), lockedNow ? AccessEventType.LOGIN_BLOCKED : AccessEventType.MFA_FAILURE,
                        step.isEmpty() ? "invalid_code" : "code_reused");
            }
            user.registerSuccessfulLogin(now);
            users.save(user);
            return Outcome.authenticated(user.getId(), sessionManager.open(user, tenant, true, client));
        });
        return finish(outcome, tenant.id(), null);
    }

    @Override
    public MfaEnrollment startMfaEnrollment(String stepToken) {
        StepClaims claims = readStep(stepToken, TokenClaims.TYPE_MFA_ENROLL);
        TenantView tenant = activeTenant(claims.tenantId());
        return tenantExecution.callInTenant(tenant.id(), "identity.mfa_enroll", () -> {
            User user = users.findById(claims.userId())
                    .filter(u -> u.canAuthenticate(Instant.now(clock)))
                    .orElseThrow(AuthenticationFailedException::invalidToken);
            return credentialService.beginMfaEnrollment(user);
        });
    }

    @Override
    public LoginResult confirmMfaEnrollment(String stepToken, String code, ClientInfo client) {
        StepClaims claims = readStep(stepToken, TokenClaims.TYPE_MFA_ENROLL);
        TenantView tenant = activeTenant(claims.tenantId());
        Outcome outcome = tenantExecution.callInTenant(tenant.id(), "identity.mfa_enroll", () -> {
            Instant now = Instant.now(clock);
            User user = users.findById(claims.userId()).orElse(null);
            if (user == null || !user.canAuthenticate(now)) {
                return Outcome.failure(claims.userId(), AccessEventType.MFA_FAILURE, "user_not_eligible");
            }
            if (!credentialService.completeMfaEnrollment(user, code)) {
                return Outcome.failure(user.getId(), AccessEventType.MFA_FAILURE, "invalid_enrollment_code");
            }
            user.registerSuccessfulLogin(now);
            users.save(user);
            return Outcome.authenticated(user.getId(), sessionManager.open(user, tenant, true, client));
        });
        return finish(outcome, tenant.id(), null);
    }

    @Override
    public void changeExpiredPassword(String stepToken, String newPassword) {
        StepClaims claims = readStep(stepToken, TokenClaims.TYPE_PASSWORD_CHANGE);
        TenantView tenant = activeTenant(claims.tenantId());
        tenantExecution.inTenant(tenant.id(), "identity.password_change", () -> {
            User user = users.findById(claims.userId())
                    .filter(u -> u.canAuthenticate(Instant.now(clock)))
                    .orElseThrow(AuthenticationFailedException::invalidToken);
            credentialService.changePassword(user, newPassword);
        });
        accessLogger.log(AccessEvent.forUser(AccessEventType.PASSWORD_CHANGED, tenant.id(), claims.userId(), null, null,
                Map.of("trigger", "expired_or_first_access")));
    }

    // =================================================================== refresh / logout

    @Override
    public LoginResult refresh(String refreshToken, ClientInfo client) {
        OpaqueToken token = OpaqueToken.parse(refreshToken).orElseThrow(AuthenticationFailedException::invalidToken);
        TenantView tenant = activeTenant(token.tenantId());
        Outcome outcome = tenantExecution.callInTenant(tenant.id(), "identity.refresh", () -> rotate(tenant, token, client));
        return finish(outcome, tenant.id(), null);
    }

    private Outcome rotate(TenantView tenant, OpaqueToken presented, ClientInfo client) {
        Instant now = Instant.now(clock);
        Optional<RefreshToken> stored = sessions.findTokenByHashForUpdate(presented.hash());
        if (stored.isEmpty()) {
            return Outcome.failure(null, AccessEventType.SESSION_EXPIRED, "refresh_unknown");
        }
        RefreshToken current = stored.get();
        AuthSession session = sessions.findById(current.getSessionId()).orElse(null);
        if (session == null) {
            return Outcome.failure(null, AccessEventType.SESSION_EXPIRED, "session_missing");
        }
        if (current.getStatus() != RefreshToken.Status.ACTIVE) {
            // reuso de token já rotacionado: possível roubo. Revoga a sessão inteira.
            sessionManager.revoke(session, "refresh_token_reuse");
            users.findById(session.getUserId()).ifPresent(user -> mailer.sendSecurityAlert(user.getEmail(), user.getName(),
                    "Sessão encerrada por segurança",
                    "Detectamos o reuso de uma credencial de sessão e encerramos a sessão por precaução. "
                            + "Se não reconhece esta atividade, altere sua senha."));
            return Outcome.failure(session.getUserId(), AccessEventType.TOKEN_REUSE_DETECTED, "refresh_reuse");
        }
        if (!current.isUsable(now) || !session.isUsable(now, properties.session().idleTimeout())) {
            session.expire(now);
            sessions.save(session);
            current.revoke();
            sessions.saveToken(current);
            return Outcome.failure(session.getUserId(), AccessEventType.SESSION_EXPIRED, "session_expired");
        }
        User user = users.findById(session.getUserId()).orElse(null);
        if (user == null || !user.canAuthenticate(now)) {
            sessionManager.revoke(session, "user_not_eligible");
            return Outcome.failure(session.getUserId(), AccessEventType.SESSION_REVOKED, "user_not_eligible");
        }
        current.markUsed(now);
        sessions.saveToken(current);
        session.touch(now);
        sessions.save(session);
        return Outcome.refreshed(user.getId(), session.getId(), sessionManager.issue(user, tenant, session, now));
    }

    @Override
    public void logout() {
        Actor actor = currentActor.get();
        if (actor.sessionId() == null) {
            return;
        }
        tenantExecution.inTenant(actor.tenantId(), "identity.logout", () ->
                sessions.findById(actor.sessionId()).ifPresent(session -> sessionManager.revoke(session, "logout")));
        accessLogger.log(AccessEvent.of(AccessEventType.LOGOUT, actor, Map.of()));
    }

    // =================================================================== credenciais por e-mail

    @Override
    public void requestPasswordReset(String tenantSlug, String email) {
        Optional<TenantView> tenant = tenantAdministration.findBySlug(tenantSlug).filter(TenantView::active);
        String masked = SensitiveDataMasker.maskEmail(email);
        if (tenant.isEmpty() || email == null) {
            accessLogger.log(AccessEvent.anonymous(AccessEventType.PASSWORD_RESET_REQUESTED, null, masked,
                    Map.of("result", "ignored_unknown_tenant")));
            return;
        }
        // Ativo: link de redefinição. Pendente de ativação (convite perdido ou expirado): novo link de
        // ativação, que invalida o anterior (decisão 524). Desativado ou inexistente: nada. A resposta é
        // a mesma em todos os casos (sem enumeração) e o envio ocorre depois do commit, em segundo plano.
        IssuedLink issued = tenantExecution.callInTenant(tenant.get().id(), "identity.password_reset", () ->
                users.findByEmail(normalizeEmailSafely(email))
                        .map(user -> switch (user.getStatus()) {
                            case ACTIVE -> issue(user, ActionToken.Purpose.PASSWORD_RESET, tenant.get());
                            case PENDING_ACTIVATION -> issue(user, ActionToken.Purpose.ACTIVATION, tenant.get());
                            case DISABLED -> null;
                        })
                        .orElse(null));
        accessLogger.log(AccessEvent.forUser(AccessEventType.PASSWORD_RESET_REQUESTED, tenant.get().id(),
                issued == null ? null : issued.userId(), null, masked, issued == null
                        ? Map.of("result", "ignored_unknown_user")
                        : Map.of("result", "sent", "purpose", issued.purpose().name())));
    }

    private IssuedLink issue(User user, ActionToken.Purpose purpose, TenantView tenant) {
        credentialService.issueCredentialLink(user, purpose, tenant);
        return new IssuedLink(user.getId(), purpose);
    }

    private record IssuedLink(UUID userId, ActionToken.Purpose purpose) {
    }

    @Override
    public void completeCredentialAction(String actionToken, String newPassword) {
        OpaqueToken token = OpaqueToken.parse(actionToken)
                .orElseThrow(() -> new BusinessRuleException("credential_link.invalid", "Link inválido ou expirado"));
        TenantView tenant = activeTenant(token.tenantId());
        CredentialService.CompletedAction completed = tenantExecution.callInTenant(tenant.id(), "identity.credential_action",
                () -> credentialService.completeAction(token, newPassword));
        accessLogger.log(AccessEvent.forUser(completed.purpose() == ActionToken.Purpose.ACTIVATION
                        ? AccessEventType.PASSWORD_CHANGED : AccessEventType.PASSWORD_RESET_COMPLETED,
                tenant.id(), completed.userId(), null, null, Map.of("purpose", completed.purpose().name())));
    }

    // =================================================================== utilitários

    private LoginResult finish(Outcome outcome, UUID tenantId, String maskedEmail) {
        if (outcome.failure()) {
            accessLogger.log(AccessEvent.forUser(outcome.eventType(), tenantId, outcome.userId(), null, maskedEmail,
                    Map.of("reason", outcome.reason())));
            throw AuthenticationFailedException.invalidCredentials();
        }
        AccessEventType type = switch (outcome.result().status()) {
            case AUTHENTICATED -> outcome.refresh() ? AccessEventType.TOKEN_REFRESHED : AccessEventType.LOGIN_SUCCESS;
            case MFA_REQUIRED, MFA_ENROLLMENT_REQUIRED -> AccessEventType.MFA_CHALLENGE;
            case PASSWORD_CHANGE_REQUIRED -> AccessEventType.LOGIN_SUCCESS;
        };
        UUID sessionId = outcome.sessionId();
        accessLogger.log(AccessEvent.forUser(type, tenantId, outcome.userId(), sessionId, maskedEmail,
                Map.of("step", outcome.reason())));
        return outcome.result();
    }

    private StepClaims readStep(String token, String type) {
        return tokenIssuer.readStepToken(token, type).orElseThrow(AuthenticationFailedException::invalidToken);
    }

    private TenantView activeTenant(UUID tenantId) {
        return tenantAdministration.findById(tenantId).filter(TenantView::active)
                .orElseThrow(AuthenticationFailedException::invalidToken);
    }

    private void notifyLockout(User user) {
        mailer.sendSecurityAlert(user.getEmail(), user.getName(), "Conta bloqueada temporariamente",
                "Sua conta foi bloqueada após tentativas de acesso inválidas. Se não foi você, contate o administrador.");
    }

    private static String normalizeEmailSafely(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    /** Resultado de uma etapa de autenticação calculado dentro da transação do tenant. */
    private record Outcome(boolean failure, UUID userId, UUID sessionId, AccessEventType eventType, String reason,
                           LoginResult result, boolean refresh) {

        static Outcome failure(UUID userId, AccessEventType type, String reason) {
            return new Outcome(true, userId, null, type, reason, null, false);
        }

        static Outcome step(UUID userId, LoginResult result, String reason) {
            return new Outcome(false, userId, null, null, reason, result, false);
        }

        static Outcome authenticated(UUID userId, LoginResult result) {
            return new Outcome(false, userId, null, null, "authenticated", result, false);
        }

        static Outcome refreshed(UUID userId, UUID sessionId, LoginResult result) {
            return new Outcome(false, userId, sessionId, null, "refreshed", result, true);
        }
    }
}
