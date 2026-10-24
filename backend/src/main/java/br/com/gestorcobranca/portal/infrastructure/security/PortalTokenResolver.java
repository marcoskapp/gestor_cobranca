package br.com.gestorcobranca.portal.infrastructure.security;

import br.com.gestorcobranca.portal.application.PortalProperties;
import br.com.gestorcobranca.portal.domain.model.PortalSession;
import br.com.gestorcobranca.portal.domain.repository.PortalSessionRepository;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.TokenActorResolver;
import br.com.gestorcobranca.shared.security.TokenClaims;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converte o token do portal ({@code typ=portal}; audiência já conferida pelo decodificador) no ator
 * {@code DEBTOR}: id = devedor, sessão do portal validada no servidor (cache local de 15 s, como as
 * sessões de usuário), escopo de credores = só o credor do portal e as permissões mínimas dos casos de
 * uso que o portal executa em nome do devedor. Revogação e saída valem em até 15 s em todas as
 * instâncias (mesmo atraso das sessões de usuário). A cadeia de segurança só aceita esse ator em
 * {@code /api/portal/v1/**} — nunca na API interna.
 */
@Component
class PortalTokenResolver implements TokenActorResolver {

    /**
     * Permissões do devedor no portal: simular, propor/aceitar/formalizar (dentro da alçada do canal),
     * consultar acordos e gerar/consultar o Pix — sempre limitadas pelo escopo do próprio devedor nos
     * módulos negotiation e payment.
     */
    static final Set<Permission> DEBTOR_PERMISSIONS = Set.copyOf(EnumSet.of(Permission.NEGOTIATION_SIMULATE,
            Permission.NEGOTIATION_PROPOSE, Permission.AGREEMENT_VIEW, Permission.AGREEMENT_FORMALIZE,
            Permission.PAYMENT_INSTRUCTION_CREATE, Permission.PAYMENT_VIEW));

    private static final Duration CACHE_TTL = Duration.ofSeconds(15);
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);
    private static final int MAX_CACHE_ENTRIES = 50_000;

    private final PortalSessionRepository sessions;
    private final TenantExecution tenantExecution;
    private final PortalProperties properties;
    private final Clock clock;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    PortalTokenResolver(PortalSessionRepository sessions, TenantExecution tenantExecution, PortalProperties properties,
                        Clock clock) {
        this.sessions = sessions;
        this.tenantExecution = tenantExecution;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String tokenType() {
        return TokenClaims.TYPE_PORTAL;
    }

    @Override
    public Optional<Actor> resolve(Jwt jwt) {
        if (jwt.getAudience() == null || !jwt.getAudience().equals(java.util.List.of(TokenClaims.PORTAL_AUDIENCE))) {
            return Optional.empty();
        }
        UUID debtorId;
        UUID tenantId;
        UUID sessionId;
        UUID creditorId;
        try {
            debtorId = UUID.fromString(jwt.getSubject());
            tenantId = UUID.fromString(jwt.getClaimAsString(TokenClaims.TENANT));
            sessionId = UUID.fromString(jwt.getClaimAsString(TokenClaims.SESSION));
            creditorId = UUID.fromString(jwt.getClaimAsString(TokenClaims.CREDITOR));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
        if (!active(tenantId, sessionId, debtorId, creditorId)) {
            return Optional.empty();
        }
        return Optional.of(new Actor(Actor.ActorType.DEBTOR, debtorId, tenantId, sessionId, "portal:debtor",
                DEBTOR_PERMISSIONS, CreditorScope.of(Set.of(creditorId))));
    }

    private boolean active(UUID tenantId, UUID sessionId, UUID debtorId, UUID creditorId) {
        Instant now = Instant.now(clock);
        Cached cached = cache.get(sessionId);
        if (cached != null && cached.checkedAt().plus(CACHE_TTL).isAfter(now)) {
            return cached.active();
        }
        boolean active = tenantExecution.callInTenant(tenantId, "portal.session_check", () -> {
            Optional<PortalSession> found = sessions.findById(sessionId)
                    .filter(s -> s.getDebtorId().equals(debtorId) && s.getCreditorId().equals(creditorId));
            if (found.isEmpty() || !found.get().usableAt(now, properties.session().idleTimeout())) {
                return false;
            }
            if (found.get().getLastSeenAt().plus(TOUCH_INTERVAL).isBefore(now)) {
                sessions.touch(sessionId, now);
            }
            return true;
        });
        if (cache.size() > MAX_CACHE_ENTRIES) {
            cache.entrySet().removeIf(e -> e.getValue().checkedAt().plus(CACHE_TTL).isBefore(now));
        }
        cache.put(sessionId, new Cached(active, now));
        return active;
    }

    private record Cached(boolean active, Instant checkedAt) {
    }
}
