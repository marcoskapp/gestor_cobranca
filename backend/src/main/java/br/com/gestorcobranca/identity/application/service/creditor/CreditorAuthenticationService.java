package br.com.gestorcobranca.identity.application.service.creditor;

import br.com.gestorcobranca.identity.application.CreditorPortalProperties;
import br.com.gestorcobranca.identity.application.IdentityProperties;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorAuthentication;
import br.com.gestorcobranca.identity.application.port.out.CreditorLoginThrottle;
import br.com.gestorcobranca.identity.application.port.out.CreditorTokenIssuer;
import br.com.gestorcobranca.identity.application.port.out.IdentityMailer;
import br.com.gestorcobranca.identity.application.port.out.TokenIssuer.StepClaims;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorActionToken;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorRefreshToken;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorSession;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorUser;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorUserStatus;
import br.com.gestorcobranca.identity.domain.repository.CreditorAccountRepository;
import br.com.gestorcobranca.identity.domain.service.OpaqueToken;
import br.com.gestorcobranca.identity.domain.service.Totp;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.crypto.BlindIndex;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.logging.SensitiveDataMasker;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.AuthenticationFailedException;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.shared.security.ThrottlePolicy;
import br.com.gestorcobranca.shared.security.TokenClaims;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Autenticação da pessoa do credor (ADR 0011). Cada etapa roda numa transação no tenant e devolve um resultado; a
 * falha só vira exceção <em>depois</em> do commit, para o contador da conta e o limite por IP ficarem gravados. Toda
 * falha responde a mesma mensagem genérica (o motivo real fica no log de acesso), com o mesmo custo de hash.
 * <p>
 * Ordem: senha → MFA (sempre; a conta ativa sempre tem autenticador) → troca da senha vencida → sessão. Nenhum token de
 * etapa dá acesso a dado; a sessão só nasce depois do MFA.
 */
@Service
class CreditorAuthenticationService implements CreditorAuthentication {

    private static final Duration RESET_INTERVAL = Duration.ofMinutes(2);
    private static final String INVALID_LINK = "Link inválido ou expirado. Peça um novo à assessoria.";

    private final String dummyHash;

    private final TenantAdministration tenants;
    private final TenantExecution tenantExecution;
    private final CreditorAccountRepository accounts;
    private final CreditorSessions sessions;
    private final CreditorCredentials credentials;
    private final CreditorPortalGate gate;
    private final CreditorTokenIssuer tokenIssuer;
    private final CreditorLoginThrottle throttle;
    private final BlindIndex blindIndex;
    private final PasswordEncoder passwordEncoder;
    private final IdentityMailer mailer;
    private final AccessLogger accessLogger;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final CurrentActor currentActor;
    private final IdentityProperties identityProperties;
    private final CreditorPortalProperties properties;
    private final Clock clock;

    CreditorAuthenticationService(TenantAdministration tenants, TenantExecution tenantExecution,
                                  CreditorAccountRepository accounts, CreditorSessions sessions,
                                  CreditorCredentials credentials, CreditorPortalGate gate, CreditorTokenIssuer tokenIssuer,
                                  CreditorLoginThrottle throttle, BlindIndex blindIndex, PasswordEncoder passwordEncoder,
                                  IdentityMailer mailer, AccessLogger accessLogger, AuditTrail auditTrail,
                                  BusinessEventLogger businessEvents, CurrentActor currentActor,
                                  IdentityProperties identityProperties, CreditorPortalProperties properties, Clock clock) {
        this.tenants = tenants;
        this.tenantExecution = tenantExecution;
        this.accounts = accounts;
        this.sessions = sessions;
        this.credentials = credentials;
        this.gate = gate;
        this.tokenIssuer = tokenIssuer;
        this.throttle = throttle;
        this.blindIndex = blindIndex;
        this.passwordEncoder = passwordEncoder;
        this.mailer = mailer;
        this.accessLogger = accessLogger;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.currentActor = currentActor;
        this.identityProperties = identityProperties;
        this.properties = properties;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Override
    public Optional<SiteView> site(String tenantSlug) {
        return activeTenant(tenantSlug).map(t -> new SiteView(t.slug(), t.tradeName()));
    }

    // =================================================================== login

    @Override
    public CreditorLoginResult login(String tenantSlug, String email, String password, CreditorClient client) {
        String masked = SensitiveDataMasker.maskEmail(email);
        Optional<TenantView> tenant = activeTenant(tenantSlug);
        if (tenant.isEmpty() || email == null || password == null) {
            passwordEncoder.matches(String.valueOf(password), dummyHash);
            accessLogger.log(AccessEvent.anonymous(AccessEventType.LOGIN_FAILURE, tenant.map(TenantView::id).orElse(null),
                    masked, Map.of("reason", tenant.isEmpty() ? "tenant_unknown_or_inactive" : "missing_fields",
                            "portal", "creditor")));
            throw AuthenticationFailedException.invalidCredentials();
        }
        TenantView t = tenant.get();
        Outcome outcome = tenantExecution.callInTenant(t.id(), "identity.creditor_login",
                () -> attemptLogin(t, email, password, client));
        return finish(outcome, t, masked);
    }

    private Outcome attemptLogin(TenantView tenant, String email, String password, CreditorClient client) {
        Instant now = Instant.now(clock);
        String ipKey = ipKey(tenant, client);
        ThrottlePolicy.State ipState = throttle.update(ipKey, now, s -> ThrottlePolicy.onRequest(s, ipLimits(), now));
        if (ipState.lockedAt(now)) {
            passwordEncoder.matches(password, dummyHash);
            return Outcome.throttled(ipState.lockedUntil(), "creditor_login");
        }
        Optional<CreditorUser> found = accounts.findUserByEmailForUpdate(normalize(email));
        if (found.isEmpty()) {
            passwordEncoder.matches(password, dummyHash);
            return ipFailure(ipKey, now, Outcome.failure(null, AccessEventType.LOGIN_FAILURE, "unknown_user"));
        }
        CreditorUser user = found.get();
        if (user.isLocked(now)) {
            passwordEncoder.matches(password, dummyHash);
            return ipFailure(ipKey, now, Outcome.failure(user.getId(), AccessEventType.LOGIN_BLOCKED, "account_locked"));
        }
        if (!user.canAuthenticate(now) || !portalEnabled(user.getCreditorId())) {
            passwordEncoder.matches(password, dummyHash);
            return ipFailure(ipKey, now, Outcome.failure(user.getId(), AccessEventType.LOGIN_FAILURE,
                    user.getStatus() != CreditorUserStatus.ACTIVE ? "user_" + user.getStatus().name().toLowerCase()
                            : "portal_disabled"));
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            boolean lockedNow = user.registerFailure(now, identityProperties.lockout().toPolicy());
            accounts.saveUser(user);
            if (lockedNow) {
                notifyLockout(user);
            }
            return ipFailure(ipKey, now, Outcome.failure(user.getId(),
                    lockedNow ? AccessEventType.LOGIN_BLOCKED : AccessEventType.LOGIN_FAILURE,
                    lockedNow ? "locked_after_failures" : "bad_password"));
        }
        if (passwordEncoder.upgradeEncoding(user.getPasswordHash())) {
            user.rehashPassword(passwordEncoder.encode(password));
            accounts.saveUser(user);
        }
        // o contador só zera quando a sessão abre: refazer a senha não dá tentativas ilimitadas do código
        String step = tokenIssuer.issueStepToken(TokenClaims.TYPE_CREDITOR_MFA, user.getId(), user.getTenantId());
        return Outcome.step(user.getId(), CreditorLoginResult.step(LoginStatus.MFA_REQUIRED, step), "mfa_required");
    }

    @Override
    public CreditorLoginResult verifyMfa(String stepToken, String code, CreditorClient client) {
        StepClaims claims = readStep(stepToken, TokenClaims.TYPE_CREDITOR_MFA);
        TenantView tenant = activeTenant(claims.tenantId());
        Outcome outcome = tenantExecution.callInTenant(tenant.id(), "identity.creditor_mfa", () -> {
            Instant now = Instant.now(clock);
            String ipKey = ipKey(tenant, client);
            ThrottlePolicy.State ipState = throttle.update(ipKey, now, s -> ThrottlePolicy.onRequest(s, ipLimits(), now));
            if (ipState.lockedAt(now)) {
                return Outcome.throttled(ipState.lockedUntil(), "creditor_mfa");
            }
            CreditorUser user = accounts.findUserForUpdate(claims.userId()).orElse(null);
            if (user == null || !user.canAuthenticate(now) || !portalEnabled(user.getCreditorId())) {
                return ipFailure(ipKey, now, Outcome.failure(claims.userId(), AccessEventType.MFA_FAILURE, "user_not_eligible"));
            }
            OptionalLong mfaStep = Totp.verify(user.getMfaSecret(), code, now);
            if (mfaStep.isEmpty() || !user.acceptMfaStep(mfaStep.getAsLong())) {
                boolean lockedNow = user.registerFailure(now, identityProperties.lockout().toPolicy());
                accounts.saveUser(user);
                if (lockedNow) {
                    notifyLockout(user);
                }
                return ipFailure(ipKey, now, Outcome.failure(user.getId(),
                        lockedNow ? AccessEventType.LOGIN_BLOCKED : AccessEventType.MFA_FAILURE,
                        mfaStep.isEmpty() ? "invalid_code" : "code_reused"));
            }
            throttle.update(ipKey, now, s -> ThrottlePolicy.onSuccess(s, now, false));
            if (user.isPasswordExpired(now, identityProperties.password().maxAge())) {
                // senha vencida só depois do MFA: a troca nunca dispensa o segundo fator
                user.registerSuccessfulVerification();
                accounts.saveUser(user);
                String step = tokenIssuer.issueStepToken(TokenClaims.TYPE_CREDITOR_PASSWORD, user.getId(), user.getTenantId());
                return Outcome.step(user.getId(), CreditorLoginResult.step(LoginStatus.PASSWORD_CHANGE_REQUIRED, step),
                        "password_change_required");
            }
            return authenticated(user, tenant, client, now);
        });
        return finish(outcome, tenant, null);
    }

    @Override
    public CreditorLoginResult changeExpiredPassword(String stepToken, String newPassword, CreditorClient client) {
        StepClaims claims = readStep(stepToken, TokenClaims.TYPE_CREDITOR_PASSWORD);
        TenantView tenant = activeTenant(claims.tenantId());
        Outcome outcome = tenantExecution.callInTenant(tenant.id(), "identity.creditor_password_expired", () -> {
            Instant now = Instant.now(clock);
            CreditorUser user = accounts.findUserForUpdate(claims.userId()).orElse(null);
            if (user == null || !user.canAuthenticate(now) || !portalEnabled(user.getCreditorId())) {
                return Outcome.failure(claims.userId(), AccessEventType.LOGIN_FAILURE, "user_not_eligible");
            }
            if (!user.isPasswordExpired(now, identityProperties.password().maxAge())) {
                // a etapa vale uma vez: depois da troca a senha deixa de estar vencida e o token reapresentado é recusado
                return Outcome.failure(user.getId(), AccessEventType.LOGIN_FAILURE, "password_not_expired");
            }
            credentials.changePassword(user, newPassword);
            sessions.revokeAll(user.getId(), "password_changed");
            mailer.sendSecurityAlert(user.getEmail(), user.getName(), "Senha do portal do credor alterada",
                    "A senha do seu acesso ao portal do credor foi alterada. Se não foi você, avise a assessoria.");
            accessLogger.log(AccessEvent.forCreditor(AccessEventType.PASSWORD_CHANGED, tenant.id(), user.getId(), null, null,
                    Map.of("trigger", "expired")));
            return authenticated(user, tenant, client, now);
        });
        return finish(outcome, tenant, null);
    }

    private Outcome authenticated(CreditorUser user, TenantView tenant, CreditorClient client, Instant now) {
        user.registerSuccessfulLogin(now);
        accounts.saveUser(user);
        CreditorSessions.Opened opened = sessions.open(user, tenant, client);
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.CREDITOR_PORTAL_LOGIN).creditor(user.getCreditorId())
                .attr("role", user.getRole().name()));
        return Outcome.authenticated(user.getId(), opened.sessionId(), opened.result());
    }

    // =================================================================== ativação (convite)

    @Override
    public ActivationStart startActivation(String invitationToken) {
        OpaqueToken token = OpaqueToken.parse(invitationToken).orElseThrow(CreditorAuthenticationService::invalidLink);
        TenantView tenant = tenants.findById(token.tenantId()).filter(TenantView::active)
                .orElseThrow(CreditorAuthenticationService::invalidLink);
        return tenantExecution.callInTenant(tenant.id(), "identity.creditor_activation_start", () -> {
            CreditorUser user = invitedUser(token).orElseThrow(CreditorAuthenticationService::invalidLink);
            // segredo pendente é mantido (recarregar a tela não invalida o QR já lido); só a conclusão confirma
            String secret = user.getMfaSecret() != null ? user.getMfaSecret() : Totp.generateSecret();
            user.startActivation(secret);
            accounts.saveUser(user);
            return new ActivationStart(user.getName(), SensitiveDataMasker.maskEmail(user.getEmail()),
                    sessions.creditorName(user.getCreditorId()), tenant.tradeName(), secret,
                    Totp.otpAuthUri(identityProperties.mfa().issuer() + " - Credor",
                            sessions.creditorName(user.getCreditorId()), secret));
        });
    }

    @Override
    public CreditorLoginResult completeActivation(String invitationToken, String newPassword, String code,
                                                  CreditorClient client) {
        OpaqueToken token = OpaqueToken.parse(invitationToken).orElseThrow(CreditorAuthenticationService::invalidLink);
        TenantView tenant = tenants.findById(token.tenantId()).filter(TenantView::active)
                .orElseThrow(CreditorAuthenticationService::invalidLink);
        Outcome outcome = tenantExecution.callInTenant(tenant.id(), "identity.creditor_activation", () -> {
            Instant now = Instant.now(clock);
            String ipKey = ipKey(tenant, client);
            ThrottlePolicy.State ipState = throttle.update(ipKey, now, s -> ThrottlePolicy.onRequest(s, ipLimits(), now));
            if (ipState.lockedAt(now)) {
                return Outcome.throttled(ipState.lockedUntil(), "creditor_activation");
            }
            CreditorActionToken action = credentials.usableToken(token, CreditorActionToken.Purpose.INVITATION).orElse(null);
            CreditorUser user = action == null ? null : accounts.findUserForUpdate(action.getUserId())
                    .filter(u -> u.getStatus() == CreditorUserStatus.INVITED && u.getMfaSecret() != null)
                    .filter(u -> portalEnabled(u.getCreditorId())).orElse(null);
            if (user == null) {
                return ipFailure(ipKey, now, Outcome.invalidLink(action == null ? null : action.getUserId()));
            }
            String hash = credentials.hashNewPassword(user, newPassword);
            OptionalLong mfaStep = Totp.verify(user.getMfaSecret(), code, now);
            if (mfaStep.isEmpty()) {
                // o convite continua válido: a pessoa corrige o código e tenta de novo (limite por IP)
                return ipFailure(ipKey, now, Outcome.invalidCode(user.getId()));
            }
            user.activate(hash, mfaStep.getAsLong(), now);
            action.markUsed(now);
            accounts.saveActionToken(action);
            credentials.invalidateLinks(user);
            long hours = Duration.between(user.getInvitedAt(), now).toHours();
            auditTrail.record(AuditEntry.of("creditor_user.activated", "creditor_user", user.getId())
                    .meta("creditorId", user.getCreditorId()).meta("role", user.getRole()));
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.CREDITOR_USER_ACTIVATED).creditor(user.getCreditorId())
                    .attr("role", user.getRole().name()).attr("hoursSinceInvite", hours));
            accessLogger.log(AccessEvent.forCreditor(AccessEventType.MFA_ENROLLED, tenant.id(), user.getId(), null, null,
                    Map.of("trigger", "invitation")));
            throttle.update(ipKey, now, s -> ThrottlePolicy.onSuccess(s, now, false));
            return authenticated(user, tenant, client, now);
        });
        return finish(outcome, tenant, null);
    }

    private Optional<CreditorUser> invitedUser(OpaqueToken token) {
        return credentials.usableToken(token, CreditorActionToken.Purpose.INVITATION)
                .flatMap(action -> accounts.findUserForUpdate(action.getUserId()))
                .filter(u -> u.getStatus() == CreditorUserStatus.INVITED)
                .filter(u -> portalEnabled(u.getCreditorId()));
    }

    // =================================================================== refresh / logout / me

    @Override
    public CreditorLoginResult refresh(String refreshToken, CreditorClient client) {
        OpaqueToken token = OpaqueToken.parse(refreshToken).orElseThrow(AuthenticationFailedException::invalidToken);
        TenantView tenant = activeTenant(token.tenantId());
        Outcome outcome = tenantExecution.callInTenant(tenant.id(), "identity.creditor_refresh",
                () -> rotate(tenant, token, client));
        return finish(outcome, tenant, null);
    }

    private Outcome rotate(TenantView tenant, OpaqueToken presented, CreditorClient client) {
        Instant now = Instant.now(clock);
        CreditorRefreshToken current = accounts.findRefreshTokenByHashForUpdate(presented.hash()).orElse(null);
        if (current == null) {
            return Outcome.failure(null, AccessEventType.SESSION_EXPIRED, "refresh_unknown");
        }
        CreditorSession session = accounts.findSession(current.getSessionId()).orElse(null);
        if (session == null) {
            return Outcome.failure(null, AccessEventType.SESSION_EXPIRED, "session_missing");
        }
        if (current.getStatus() == CreditorRefreshToken.Status.REVOKED) {
            // sessão encerrada (saída, revogação, limite de sessões, senha trocada): não é roubo, sem alerta
            return Outcome.failure(session.getUserId(), AccessEventType.SESSION_REVOKED, "refresh_revoked");
        }
        if (current.getStatus() == CreditorRefreshToken.Status.USED) {
            // reuso de token já rotacionado: possível roubo. Revoga a sessão inteira e avisa a pessoa.
            sessions.revoke(session, "refresh_token_reuse");
            accounts.findUser(session.getUserId()).ifPresent(user -> mailer.sendSecurityAlert(user.getEmail(),
                    user.getName(), "Sessão do portal do credor encerrada por segurança",
                    "Detectamos o reuso de uma credencial de sessão do portal do credor e encerramos a sessão por precaução. "
                            + "Se não reconhece esta atividade, altere sua senha e avise a assessoria."));
            return Outcome.failure(session.getUserId(), AccessEventType.TOKEN_REUSE_DETECTED, "refresh_reuse");
        }
        if (!current.isUsable(now) || !session.isUsable(now, properties.idleTimeout())) {
            session.expire(now);
            accounts.saveSession(session);
            current.revoke();
            accounts.saveRefreshToken(current);
            return Outcome.failure(session.getUserId(), AccessEventType.SESSION_EXPIRED, "session_expired");
        }
        CreditorUser user = accounts.findUser(session.getUserId()).orElse(null);
        if (user == null || user.getStatus() != CreditorUserStatus.ACTIVE || !portalEnabled(user.getCreditorId())) {
            sessions.revoke(session, "user_not_eligible");
            return Outcome.failure(session.getUserId(), AccessEventType.SESSION_REVOKED, "user_not_eligible");
        }
        current.markUsed(now);
        accounts.saveRefreshToken(current);
        session.touch(now);
        accounts.saveSession(session);
        return Outcome.refreshed(user.getId(), session.getId(), sessions.issue(user, tenant, session));
    }

    @Override
    public void logout() {
        Actor actor = requireCreditor();
        tenantExecution.inTenant(actor.tenantId(), "identity.creditor_logout", () ->
                accounts.findSession(actor.sessionId()).ifPresent(session -> sessions.revoke(session, "logout")));
        accessLogger.log(AccessEvent.of(AccessEventType.LOGOUT, actor, Map.of("portal", "creditor")));
    }

    @Override
    public CreditorMeView me() {
        Actor actor = requireCreditor();
        TenantView tenant = tenants.findById(actor.tenantId()).orElseThrow(ScopeAccessDeniedException::new);
        return tenantExecution.callInTenant(actor.tenantId(), "identity.creditor_me", () -> {
            CreditorUser user = accounts.findUser(actor.id()).orElseThrow(ScopeAccessDeniedException::new);
            return sessions.me(user, tenant);
        });
    }

    // =================================================================== redefinição de senha

    @Override
    public void requestPasswordReset(String tenantSlug, String email, CreditorClient client) {
        String masked = SensitiveDataMasker.maskEmail(email);
        Optional<TenantView> tenant = activeTenant(tenantSlug);
        if (tenant.isEmpty() || email == null) {
            accessLogger.log(AccessEvent.anonymous(AccessEventType.PASSWORD_RESET_REQUESTED, null, masked,
                    Map.of("result", "ignored_unknown_tenant", "portal", "creditor")));
            return;
        }
        TenantView t = tenant.get();
        UUID issuedFor = tenantExecution.callInTenant(t.id(), "identity.creditor_password_reset", () -> {
            Instant now = Instant.now(clock);
            ThrottlePolicy.State ip = throttle.update(ipKey(t, client), now, s -> ThrottlePolicy.onRequest(s, ipLimits(), now));
            if (ip.lockedAt(now)) {
                return null; // mesma resposta 202: sem enumeração e sem e-mail
            }
            // leitura sem trava: a conclusão trava o link e depois a conta; travar a conta aqui inverteria a ordem
            return accounts.findUserByEmail(normalize(email))
                    .filter(u -> u.getStatus() == CreditorUserStatus.ACTIVE && portalEnabled(u.getCreditorId()))
                    // um link por conta a cada 2 min: pedidos em série não inundam a caixa nem invalidam o link recebido
                    .filter(u -> !credentials.hasRecentPasswordReset(u, RESET_INTERVAL))
                    .map(user -> {
                        credentials.issuePasswordReset(user, t);
                        return user.getId();
                    }).orElse(null);
        });
        accessLogger.log(AccessEvent.forCreditor(AccessEventType.PASSWORD_RESET_REQUESTED, t.id(), issuedFor, null, masked,
                Map.of("result", issuedFor == null ? "ignored" : "sent")));
    }

    @Override
    public void completePasswordReset(String resetToken, String newPassword, CreditorClient client) {
        OpaqueToken token = OpaqueToken.parse(resetToken).orElseThrow(CreditorAuthenticationService::invalidLink);
        TenantView tenant = tenants.findById(token.tenantId()).filter(TenantView::active)
                .orElseThrow(CreditorAuthenticationService::invalidLink);
        UUID userId = tenantExecution.callInTenant(tenant.id(), "identity.creditor_password_reset_complete", () -> {
            Instant now = Instant.now(clock);
            String ipKey = ipKey(tenant, client);
            ThrottlePolicy.State ip = throttle.update(ipKey, now, s -> ThrottlePolicy.onRequest(s, ipLimits(), now));
            if (ip.lockedAt(now)) {
                throw new LoginThrottledException(ip.lockedUntil());
            }
            CreditorActionToken action = credentials.usableToken(token, CreditorActionToken.Purpose.PASSWORD_RESET)
                    .orElseThrow(CreditorAuthenticationService::invalidLink);
            CreditorUser user = accounts.findUserForUpdate(action.getUserId())
                    .filter(u -> u.getStatus() == CreditorUserStatus.ACTIVE)
                    .orElseThrow(CreditorAuthenticationService::invalidLink);
            credentials.changePassword(user, newPassword);
            action.markUsed(now);
            accounts.saveActionToken(action);
            sessions.revokeAll(user.getId(), "password_reset");
            return user.getId();
        });
        accessLogger.log(AccessEvent.forCreditor(AccessEventType.PASSWORD_RESET_COMPLETED, tenant.id(), userId, null, null,
                Map.of()));
    }

    // =================================================================== utilitários

    private CreditorLoginResult finish(Outcome outcome, TenantView tenant, String maskedEmail) {
        if (outcome.throttledUntil() != null) {
            accessLogger.log(AccessEvent.anonymous(AccessEventType.RATE_LIMITED, tenant.id(), maskedEmail,
                    Map.of("operation", outcome.reason())));
            throw new LoginThrottledException(outcome.throttledUntil());
        }
        if (outcome.failure()) {
            accessLogger.log(AccessEvent.forCreditor(outcome.eventType(), tenant.id(), outcome.userId(), null, maskedEmail,
                    Map.of("reason", outcome.reason())));
            if (outcome.invalidLink()) {
                throw invalidLink();
            }
            if (outcome.invalidCode()) {
                throw new BusinessRuleException("creditor_activation.invalid_code",
                        "Código do autenticador inválido. Confira o horário do celular e digite o código atual.");
            }
            throw AuthenticationFailedException.invalidCredentials();
        }
        AccessEventType type = switch (outcome.result().status()) {
            case AUTHENTICATED -> outcome.refresh() ? AccessEventType.TOKEN_REFRESHED : AccessEventType.LOGIN_SUCCESS;
            case MFA_REQUIRED -> AccessEventType.MFA_CHALLENGE;
            case PASSWORD_CHANGE_REQUIRED -> AccessEventType.MFA_SUCCESS;
        };
        accessLogger.log(AccessEvent.forCreditor(type, tenant.id(), outcome.userId(), outcome.sessionId(), maskedEmail,
                Map.of("step", outcome.reason())));
        return outcome.result();
    }

    private Outcome ipFailure(String ipKey, Instant now, Outcome outcome) {
        throttle.update(ipKey, now, s -> ThrottlePolicy.onFailure(s, ipLimits(), now));
        return outcome;
    }

    private ThrottlePolicy.Limits ipLimits() {
        CreditorPortalProperties.Ip ip = properties.ip();
        return new ThrottlePolicy.Limits(ip.maxFailures(), ip.maxRequests(), ip.window(), ip.baseLock(), ip.maxLock());
    }

    private String ipKey(TenantView tenant, CreditorClient client) {
        String ip = client == null || client.ip() == null || client.ip().isBlank() ? "unknown" : client.ip();
        return blindIndex.of(tenant.id(), "creditor_portal.ip", ip);
    }

    private boolean portalEnabled(UUID creditorId) {
        return gate.isOpen(creditorId);
    }

    private Actor requireCreditor() {
        Actor actor = currentActor.get();
        if (!actor.isCreditor() || actor.sessionId() == null) {
            throw new ScopeAccessDeniedException();
        }
        return actor;
    }

    private StepClaims readStep(String token, String type) {
        return tokenIssuer.readStepToken(token, type).orElseThrow(AuthenticationFailedException::invalidToken);
    }

    private Optional<TenantView> activeTenant(String slug) {
        if (slug == null || slug.isBlank() || slug.length() > 50) {
            return Optional.empty();
        }
        return tenants.findBySlug(slug).filter(TenantView::active).filter(t -> !t.platform());
    }

    private TenantView activeTenant(UUID tenantId) {
        return tenants.findById(tenantId).filter(TenantView::active)
                .orElseThrow(AuthenticationFailedException::invalidToken);
    }

    private void notifyLockout(CreditorUser user) {
        mailer.sendSecurityAlert(user.getEmail(), user.getName(), "Acesso ao portal do credor bloqueado temporariamente",
                "Seu acesso ao portal do credor foi bloqueado após tentativas inválidas. Se não foi você, avise a assessoria.");
    }

    private static String normalize(String email) {
        try {
            return User.normalizeEmail(email);
        } catch (BusinessRuleException e) {
            return "";
        }
    }

    private static BusinessRuleException invalidLink() {
        return new BusinessRuleException("creditor_link.invalid", INVALID_LINK);
    }

    /** Resultado de uma etapa calculado dentro da transação do tenant; a exceção é lançada depois do commit. */
    private record Outcome(boolean failure, UUID userId, UUID sessionId, AccessEventType eventType, String reason,
                           CreditorLoginResult result, boolean refresh, Instant throttledUntil, boolean invalidLink,
                           boolean invalidCode) {

        static Outcome failure(UUID userId, AccessEventType type, String reason) {
            return new Outcome(true, userId, null, type, reason, null, false, null, false, false);
        }

        static Outcome invalidLink(UUID userId) {
            return new Outcome(true, userId, null, AccessEventType.LOGIN_FAILURE, "invitation_invalid", null, false, null,
                    true, false);
        }

        static Outcome invalidCode(UUID userId) {
            return new Outcome(true, userId, null, AccessEventType.MFA_FAILURE, "activation_invalid_code", null, false,
                    null, false, true);
        }

        static Outcome throttled(Instant until, String operation) {
            return new Outcome(true, null, null, AccessEventType.RATE_LIMITED, operation, null, false, until, false, false);
        }

        static Outcome step(UUID userId, CreditorLoginResult result, String reason) {
            return new Outcome(false, userId, null, null, reason, result, false, null, false, false);
        }

        static Outcome authenticated(UUID userId, UUID sessionId, CreditorLoginResult result) {
            return new Outcome(false, userId, sessionId, null, "authenticated", result, false, null, false, false);
        }

        static Outcome refreshed(UUID userId, UUID sessionId, CreditorLoginResult result) {
            return new Outcome(false, userId, sessionId, null, "refreshed", result, true, null, false, false);
        }
    }
}
