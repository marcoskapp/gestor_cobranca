package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.IdentityProperties;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.AuthenticatedUserView;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.ClientInfo;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.LoginResult;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.LoginStatus;
import br.com.gestorcobranca.identity.application.port.out.TokenIssuer;
import br.com.gestorcobranca.identity.application.port.out.TokenIssuer.AccessClaims;
import br.com.gestorcobranca.identity.application.port.out.TokenIssuer.IssuedToken;
import br.com.gestorcobranca.identity.domain.model.AuthSession;
import br.com.gestorcobranca.identity.domain.model.RefreshToken;
import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.repository.SessionRepository;
import br.com.gestorcobranca.identity.domain.service.OpaqueToken;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Criação, rotação e revogação de sessões. Sempre executado dentro da transação do tenant do
 * usuário (chamado pelos serviços de autenticação).
 */
@Component
class SessionManager {

    private final SessionRepository sessions;
    private final TokenIssuer tokenIssuer;
    private final AccessProfileResolver profileResolver;
    private final IdentityProperties properties;
    private final Clock clock;

    SessionManager(SessionRepository sessions, TokenIssuer tokenIssuer, AccessProfileResolver profileResolver,
                   IdentityProperties properties, Clock clock) {
        this.sessions = sessions;
        this.tokenIssuer = tokenIssuer;
        this.profileResolver = profileResolver;
        this.properties = properties;
        this.clock = clock;
    }

    /** Abre uma nova sessão, aplicando o limite de sessões simultâneas por usuário. */
    @Transactional(propagation = Propagation.MANDATORY)
    LoginResult open(User user, TenantView tenant, boolean mfaVerified, ClientInfo client) {
        Instant now = Instant.now(clock);
        enforceSessionLimit(user.getId(), now);
        AuthSession session = sessions.save(new AuthSession(user.getId(), mfaVerified, now,
                properties.session().refreshTokenTtl(), client.ip(), client.userAgent()));
        return issue(user, tenant, session, now);
    }

    /** Emite novo access token e novo refresh token para uma sessão existente. */
    @Transactional(propagation = Propagation.MANDATORY)
    LoginResult issue(User user, TenantView tenant, AuthSession session, Instant now) {
        AccessProfileResolver.AccessProfile profile = profileResolver.resolve(user);
        IssuedToken access = tokenIssuer.issueAccessToken(new AccessClaims(user.getId(), user.getTenantId(),
                session.getId(), user.getName(), profile.permissionNames(), user.isAllCreditors(), user.getCreditorIds()));
        OpaqueToken refresh = OpaqueToken.generate(user.getTenantId());
        Instant refreshExpiry = session.getAbsoluteExpiresAt();
        sessions.saveToken(new RefreshToken(session.getId(), refresh.hash(), refreshExpiry));
        AuthenticatedUserView view = new AuthenticatedUserView(user.getId(), user.getTenantId(), tenant.slug(),
                tenant.tradeName(), user.getName(), user.getEmail(), profile.roleCodes(), profile.permissionNames(),
                user.isAllCreditors(), user.getCreditorIds(), user.isMfaEnabled());
        return new LoginResult(LoginStatus.AUTHENTICATED, access.value(), access.expiresAt(), refresh.value(),
                refreshExpiry, null, view);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void revoke(AuthSession session, String reason) {
        Instant now = Instant.now(clock);
        session.revoke(now, reason);
        sessions.save(session);
        sessions.findActiveTokensBySession(session.getId()).forEach(token -> {
            token.revoke();
            sessions.saveToken(token);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    int revokeAll(UUID userId, String reason) {
        List<AuthSession> active = sessions.findActiveByUser(userId);
        active.forEach(session -> revoke(session, reason));
        return active.size();
    }

    private void enforceSessionLimit(UUID userId, Instant now) {
        List<AuthSession> active = sessions.findActiveByUser(userId).stream()
                .sorted(Comparator.comparing(AuthSession::getLastSeenAt))
                .toList();
        int excess = active.size() - properties.session().maxSessionsPerUser() + 1;
        for (int i = 0; i < excess; i++) {
            revoke(active.get(i), "session_limit");
        }
    }
}
