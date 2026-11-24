package br.com.gestorcobranca.identity.infrastructure.security;

import br.com.gestorcobranca.identity.application.CreditorPortalProperties;
import br.com.gestorcobranca.identity.application.service.creditor.CreditorPortalGate;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorSession;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorUser;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorUserStatus;
import br.com.gestorcobranca.identity.domain.repository.CreditorAccountRepository;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.TokenActorResolver;
import br.com.gestorcobranca.shared.security.TokenClaims;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Converte o token do portal do credor ({@code typ=creditor}) no ator {@code CREDITOR} (ADR 0011). <b>Sem cache</b>: a
 * cada requisição confere no banco a sessão ativa (inatividade e prazo), a conta ativa, o portal liberado com o credor
 * ativo, a assessoria ativa e o credor do token igual ao da sessão — revogar, desativar o portal ou trocar o perfil vale na requisição seguinte.
 * As permissões vêm do perfil da conta no banco, nunca do token. A cadeia de segurança só aceita esse ator em
 * {@code /api/creditor/v1/**}.
 */
@Component
class CreditorTokenResolver implements TokenActorResolver {

    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final CreditorAccountRepository accounts;
    private final CreditorPortalGate gate;
    private final TenantAdministration tenants;
    private final TenantExecution tenantExecution;
    private final CreditorPortalProperties properties;
    private final Clock clock;

    CreditorTokenResolver(CreditorAccountRepository accounts, CreditorPortalGate gate, TenantAdministration tenants,
                          TenantExecution tenantExecution, CreditorPortalProperties properties, Clock clock) {
        this.accounts = accounts;
        this.gate = gate;
        this.tenants = tenants;
        this.tenantExecution = tenantExecution;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String tokenType() {
        return TokenClaims.TYPE_CREDITOR;
    }

    @Override
    public Optional<Actor> resolve(Jwt jwt) {
        if (jwt.getAudience() == null || !jwt.getAudience().equals(List.of(TokenClaims.CREDITOR_AUDIENCE))) {
            return Optional.empty();
        }
        UUID userId;
        UUID tenantId;
        UUID sessionId;
        UUID creditorId;
        try {
            userId = UUID.fromString(jwt.getSubject());
            tenantId = UUID.fromString(jwt.getClaimAsString(TokenClaims.TENANT));
            sessionId = UUID.fromString(jwt.getClaimAsString(TokenClaims.SESSION));
            creditorId = UUID.fromString(jwt.getClaimAsString(TokenClaims.CREDITOR));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
        return tenantExecution.callInTenant(tenantId, "identity.creditor_session_check", () -> {
            Instant now = Instant.now(clock);
            CreditorSession session = accounts.findSession(sessionId).orElse(null);
            if (session == null || !session.getUserId().equals(userId) || !session.getCreditorId().equals(creditorId)
                    || !session.isUsable(now, properties.idleTimeout())) {
                return Optional.<Actor>empty();
            }
            CreditorUser user = accounts.findUser(userId).orElse(null);
            if (user == null || user.getStatus() != CreditorUserStatus.ACTIVE || !user.getCreditorId().equals(creditorId)) {
                return Optional.<Actor>empty();
            }
            // portal liberado e credor ativo (desativar o credor fecha o portal); assessoria ativa
            if (!gate.isOpen(creditorId) || tenants.findById(tenantId).filter(TenantView::active).isEmpty()) {
                return Optional.<Actor>empty();
            }
            if (session.getLastSeenAt().plus(TOUCH_INTERVAL).isBefore(now)) {
                accounts.touchSession(sessionId, now);
            }
            return Optional.of(Actor.creditor(tenantId, userId, sessionId, creditorId, user.getRole().permissions()));
        });
    }
}
