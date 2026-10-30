package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.publicapi.application.port.in.gateway.IntegrationAuthentication;
import br.com.gestorcobranca.publicapi.application.port.out.TokenRouteDirectory;
import br.com.gestorcobranca.publicapi.domain.model.IntegrationToken;
import br.com.gestorcobranca.publicapi.domain.model.TokenScope;
import br.com.gestorcobranca.publicapi.domain.model.TokenSecret;
import br.com.gestorcobranca.publicapi.domain.model.TokenState;
import br.com.gestorcobranca.publicapi.domain.repository.IntegrationTokenRepository;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Autenticação do token de integração a cada requisição: formato → rota global pelo prefixo → token (sob RLS da
 * assessoria) → hash em tempo constante → situação (revogado/expirado) → assessoria e credor ativos → IPs permitidos.
 * Sem cache: revogação vale na requisição seguinte. Recusas vão ao log de acesso com o motivo real.
 */
@Service
class IntegrationAuthenticationService implements IntegrationAuthentication {

    private final TokenRouteDirectory routes;
    private final IntegrationTokenRepository tokens;
    private final TenantAdministration tenants;
    private final CreditorUseCases creditors;
    private final TokenHashing hashing;
    private final TenantExecution tenantExecution;
    private final TokenRateLimiter rateLimiter;
    private final AccessLogger accessLogger;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    IntegrationAuthenticationService(TokenRouteDirectory routes, IntegrationTokenRepository tokens,
                                     TenantAdministration tenants, CreditorUseCases creditors, TokenHashing hashing,
                                     TenantExecution tenantExecution, TokenRateLimiter rateLimiter,
                                     AccessLogger accessLogger, MeterRegistry meterRegistry, Clock clock) {
        this.routes = routes;
        this.tokens = tokens;
        this.tenants = tenants;
        this.creditors = creditors;
        this.hashing = hashing;
        this.tenantExecution = tenantExecution;
        this.rateLimiter = rateLimiter;
        this.accessLogger = accessLogger;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Override
    public Result authenticate(String presentedToken, String clientIp) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return reject("missing", 401, null, null, null);
        }
        Optional<TokenSecret> parsed = TokenSecret.parse(presentedToken);
        if (parsed.isEmpty()) {
            return reject("malformed", 401, null, null, null);
        }
        TokenSecret secret = parsed.get();
        Optional<TokenRouteDirectory.Route> route = routes.resolve(secret.prefix());
        if (route.isEmpty()) {
            // hash fictício: o tempo de resposta não revela se o prefixo existe
            hashing.hasher().hash(secret);
            return reject("unknown", 401, null, null, secret.displayPrefix());
        }
        UUID tenantId = route.get().tenantId();
        Instant now = Instant.now(clock);
        Result result = tenantExecution.callAsReadOnly(Actor.system(tenantId, "publicapi.authentication"),
                () -> verify(tenantId, route.get().tokenId(), secret, clientIp, now));
        if (result instanceof Rejected rejected) {
            return reject(rejected.reason(), rejected.status(), tenantId, route.get().tokenId(), secret.displayPrefix());
        }
        meterRegistry.counter("publicapi.auth", "outcome", "accepted").increment();
        return result;
    }

    private Result verify(UUID tenantId, UUID tokenId, TokenSecret secret, String clientIp, Instant now) {
        IntegrationToken token = tokens.findById(tokenId).orElse(null);
        if (token == null || !token.matches(secret, hashing.hasher(), now)) {
            return new Rejected(token == null ? "unknown" : "secret_mismatch", 401, tenantId, secret.displayPrefix());
        }
        TokenState state = token.stateAt(now);
        if (state != TokenState.ACTIVE) {
            return new Rejected(state == TokenState.REVOKED ? "revoked" : "expired", 401, tenantId, secret.displayPrefix());
        }
        if (tenants.findById(tenantId).filter(t -> t.active()).isEmpty()) {
            return new Rejected("tenant_inactive", 401, tenantId, secret.displayPrefix());
        }
        try {
            creditors.requireActive(token.getCreditorId());
        } catch (DomainException e) {
            return new Rejected("creditor_inactive", 403, tenantId, secret.displayPrefix());
        }
        if (!IpAllowList.allows(token.getAllowedIps(), clientIp)) {
            return new Rejected("ip_not_allowed", 403, tenantId, secret.displayPrefix());
        }
        Actor actor = new Actor(Actor.ActorType.INTEGRATION, token.getId(), tenantId, null,
                "integration:" + token.getDisplayPrefix(), TokenScope.permissionsOf(token.getScopes()),
                CreditorScope.of(List.of(token.getCreditorId())));
        return new Authenticated(actor, TokenScope.codesOf(token.getScopes()), token.getId(), token.getDisplayPrefix(),
                token.getCreditorId(), token.getRateLimitPerMinute());
    }

    private Rejected reject(String reason, int status, UUID tenantId, UUID tokenId, String prefix) {
        meterRegistry.counter("publicapi.auth", "outcome", reason).increment();
        accessLogger.log(new AccessEvent(AccessEventType.INTEGRATION_TOKEN_REJECTED, false, tenantId, "INTEGRATION",
                tokenId, null, prefix, Map.of("reason", reason)));
        return new Rejected(reason, status, tenantId, prefix);
    }

    @Override
    public RateDecision acquire(UUID tokenId, int limitPerMinute) {
        return rateLimiter.acquire(tokenId, limitPerMinute);
    }
}
