package br.com.gestorcobranca.identity.infrastructure.security;

import br.com.gestorcobranca.identity.application.IdentityProperties;
import br.com.gestorcobranca.identity.domain.model.AuthSession;
import br.com.gestorcobranca.identity.domain.repository.SessionRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Resolve o ator de um access token de usuário verificando a sessão no servidor. Para não
 * consultar o banco a cada requisição, o resultado é mantido em cache local por
 * {@link #CACHE_TTL}; esse é o atraso máximo para uma revogação valer em outra instância.
 * O {@code last_seen_at} da sessão é atualizado no máximo a cada {@link #TOUCH_INTERVAL}.
 */
@Component
class UserAccessTokenResolver implements TokenActorResolver {

    private static final Duration CACHE_TTL = Duration.ofSeconds(15);
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);
    private static final int MAX_CACHE_ENTRIES = 50_000;

    private final SessionRepository sessions;
    private final TenantExecution tenantExecution;
    private final IdentityProperties properties;
    private final Clock clock;
    private final Map<UUID, CachedSession> cache = new ConcurrentHashMap<>();

    UserAccessTokenResolver(SessionRepository sessions, TenantExecution tenantExecution, IdentityProperties properties,
                            Clock clock) {
        this.sessions = sessions;
        this.tenantExecution = tenantExecution;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String tokenType() {
        return TokenClaims.TYPE_ACCESS;
    }

    @Override
    public Optional<Actor> resolve(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID tenantId = UUID.fromString(jwt.getClaimAsString(TokenClaims.TENANT));
        UUID sessionId = UUID.fromString(jwt.getClaimAsString(TokenClaims.SESSION));
        if (!sessionIsActive(tenantId, sessionId, userId)) {
            return Optional.empty();
        }
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        List<String> permissionClaims = jwt.getClaimAsStringList(TokenClaims.PERMISSIONS);
        if (permissionClaims != null) {
            for (String name : permissionClaims) {
                try {
                    permissions.add(Permission.valueOf(name));
                } catch (IllegalArgumentException ignored) {
                    // permissão removida do catálogo após a emissão do token: simplesmente não é concedida
                }
            }
        }
        Boolean all = jwt.getClaim(TokenClaims.ALL_CREDITORS);
        List<String> creditors = jwt.getClaimAsStringList(TokenClaims.CREDITORS);
        CreditorScope scope = Boolean.TRUE.equals(all) ? CreditorScope.ALL
                : CreditorScope.of(creditors == null ? Set.of()
                : creditors.stream().map(UUID::fromString).collect(Collectors.toSet()));
        return Optional.of(new Actor(Actor.ActorType.USER, userId, tenantId, sessionId,
                jwt.getClaimAsString(TokenClaims.NAME), permissions, scope));
    }

    private boolean sessionIsActive(UUID tenantId, UUID sessionId, UUID userId) {
        Instant now = Instant.now(clock);
        CachedSession cached = cache.get(sessionId);
        if (cached != null && cached.checkedAt().plus(CACHE_TTL).isAfter(now)) {
            return cached.active();
        }
        boolean active = tenantExecution.callInTenant(tenantId, "identity.session_check", () -> {
            Optional<AuthSession> found = sessions.findById(sessionId).filter(s -> s.getUserId().equals(userId));
            if (found.isEmpty()) {
                return false;
            }
            AuthSession session = found.get();
            if (!session.isUsable(now, properties.session().idleTimeout())) {
                // a marcação como EXPIRED é feita pelo job de limpeza; aqui apenas negamos o acesso
                return false;
            }
            if (session.getLastSeenAt().plus(TOUCH_INTERVAL).isBefore(now)) {
                // atualização pontual (sem versão) para não conflitar com requisições paralelas
                sessions.touch(sessionId, now);
            }
            return true;
        });
        if (cache.size() > MAX_CACHE_ENTRIES) {
            cache.entrySet().removeIf(e -> e.getValue().checkedAt().plus(CACHE_TTL).isBefore(now));
        }
        cache.put(sessionId, new CachedSession(active, now));
        return active;
    }

    private record CachedSession(boolean active, Instant checkedAt) {
    }
}
