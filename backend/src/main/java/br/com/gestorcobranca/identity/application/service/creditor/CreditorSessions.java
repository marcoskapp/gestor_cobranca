package br.com.gestorcobranca.identity.application.service.creditor;

import br.com.gestorcobranca.identity.application.CreditorPortalProperties;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorAuthentication.CreditorClient;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorAuthentication.CreditorLoginResult;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorAuthentication.CreditorMeView;
import br.com.gestorcobranca.identity.application.port.in.creditor.CreditorAuthentication.LoginStatus;
import br.com.gestorcobranca.identity.application.port.out.CreditorTokenIssuer;
import br.com.gestorcobranca.identity.application.port.out.TokenIssuer.IssuedToken;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorRefreshToken;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorSession;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorUser;
import br.com.gestorcobranca.identity.domain.repository.CreditorAccountRepository;
import br.com.gestorcobranca.identity.domain.service.OpaqueToken;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Abertura, renovação e revogação das sessões do credor, sempre na transação (e no tenant) de quem chama. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class CreditorSessions {

    private final CreditorAccountRepository accounts;
    private final CreditorTokenIssuer tokenIssuer;
    private final CreditorUseCases creditors;
    private final CreditorPortalProperties properties;
    private final Clock clock;

    CreditorSessions(CreditorAccountRepository accounts, CreditorTokenIssuer tokenIssuer, CreditorUseCases creditors,
                     CreditorPortalProperties properties, Clock clock) {
        this.accounts = accounts;
        this.tokenIssuer = tokenIssuer;
        this.creditors = creditors;
        this.properties = properties;
        this.clock = clock;
    }

    /** Sessão aberta e o resultado do login (o id da sessão vai para o log de acesso). */
    record Opened(UUID sessionId, CreditorLoginResult result) {
    }

    /** Abre uma sessão (depois do MFA), aplicando o limite de sessões simultâneas. */
    Opened open(CreditorUser user, TenantView tenant, CreditorClient client) {
        Instant now = Instant.now(clock);
        List<CreditorSession> active = accounts.findActiveSessionsByUser(user.getId()).stream()
                .sorted(Comparator.comparing(CreditorSession::getLastSeenAt)).toList();
        int excess = active.size() - properties.maxSessions() + 1;
        for (int i = 0; i < excess; i++) {
            revoke(active.get(i), "session_limit");
        }
        CreditorSession session = accounts.saveSession(new CreditorSession(user.getId(), user.getCreditorId(), now,
                properties.sessionLifetime(), client == null ? null : client.ip(), client == null ? null : client.userAgent()));
        return new Opened(session.getId(), issue(user, tenant, session));
    }

    /** Novo token de acesso e novo refresh token (rotação) para uma sessão existente. */
    CreditorLoginResult issue(CreditorUser user, TenantView tenant, CreditorSession session) {
        IssuedToken access = tokenIssuer.issueAccessToken(user.getId(), user.getTenantId(), session.getId(),
                user.getCreditorId());
        OpaqueToken refresh = OpaqueToken.generate(user.getTenantId());
        accounts.saveRefreshToken(new CreditorRefreshToken(session.getId(), refresh.hash(), session.getAbsoluteExpiresAt()));
        return new CreditorLoginResult(LoginStatus.AUTHENTICATED, access.value(), access.expiresAt(), refresh.value(),
                session.getAbsoluteExpiresAt(), null, me(user, tenant));
    }

    CreditorMeView me(CreditorUser user, TenantView tenant) {
        String creditorName = creditorName(user.getCreditorId());
        return new CreditorMeView(user.getId(), user.getName(), user.getEmail(), user.getRole().name(),
                user.getRole().label(), user.getRole().permissions().stream().map(Permission::name)
                .collect(Collectors.toCollection(java.util.TreeSet::new)), user.getCreditorId(), creditorName, tenant.slug(),
                tenant.tradeName(), user.getLastLoginAt());
    }

    String creditorName(UUID creditorId) {
        CreditorUseCases.CreditorView creditor = creditors.get(creditorId);
        return creditor.tradeName() != null && !creditor.tradeName().isBlank() ? creditor.tradeName() : creditor.legalName();
    }

    void revoke(CreditorSession session, String reason) {
        session.revoke(Instant.now(clock), reason);
        accounts.saveSession(session);
        accounts.findActiveRefreshTokens(session.getId()).forEach(token -> {
            token.revoke();
            accounts.saveRefreshToken(token);
        });
    }

    int revokeAll(UUID userId, String reason) {
        List<CreditorSession> active = accounts.findActiveSessionsByUser(userId);
        active.forEach(session -> revoke(session, reason));
        return active.size();
    }

    int revokeAllOfCreditor(UUID creditorId, String reason) {
        List<CreditorSession> active = accounts.findActiveSessionsByCreditor(creditorId);
        active.forEach(session -> revoke(session, reason));
        return active.size();
    }
}
