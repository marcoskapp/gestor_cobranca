package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.publicapi.application.port.in.gateway.IntegrationAuthentication;
import br.com.gestorcobranca.publicapi.application.port.out.ClientAddressRules;
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
 * assessoria) → hash em tempo constante → situação (revogado/expirado → 401; pausado pela plataforma → 403
 * {@code auth.token_paused}) → assessoria e credor ativos → IPs permitidos.
 * <p>
 * <b>Sem cache</b> (decisão 767): o token é lido do banco a cada requisição, então revogação, pausa e retomada valem na
 * requisição seguinte em <em>todas</em> as instâncias atrás do balanceador, sem invalidação a coordenar. Recusas vão ao
 * log de acesso com o motivo real; quando o segredo confere (o portador do token está provado), a recusa também é
 * atribuída ao token e ao credor para a medição do tráfego recusado (decisão 761).
 */
@Service
class IntegrationAuthenticationService implements IntegrationAuthentication {

    static final String PAUSED = "paused";

    private final TokenRouteDirectory routes;
    private final IntegrationTokenRepository tokens;
    private final TenantAdministration tenants;
    private final CreditorUseCases creditors;
    private final TokenHashing hashing;
    private final ClientAddressRules addressRules;
    private final TenantExecution tenantExecution;
    private final TokenRateLimiter rateLimiter;
    private final AccessLogger accessLogger;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    IntegrationAuthenticationService(TokenRouteDirectory routes, IntegrationTokenRepository tokens,
                                     TenantAdministration tenants, CreditorUseCases creditors, TokenHashing hashing,
                                     ClientAddressRules addressRules, TenantExecution tenantExecution, TokenRateLimiter rateLimiter,
                                     AccessLogger accessLogger, MeterRegistry meterRegistry, Clock clock) {
        this.routes = routes;
        this.tokens = tokens;
        this.tenants = tenants;
        this.creditors = creditors;
        this.hashing = hashing;
        this.addressRules = addressRules;
        this.tenantExecution = tenantExecution;
        this.rateLimiter = rateLimiter;
        this.accessLogger = accessLogger;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Override
    public Result authenticate(String presentedToken, String clientIp) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return reject(new Rejected("missing", 401, null, null, null, null), null);
        }
        Optional<TokenSecret> parsed = TokenSecret.parse(presentedToken);
        if (parsed.isEmpty()) {
            return reject(new Rejected("malformed", 401, null, null, null, null), null);
        }
        TokenSecret secret = parsed.get();
        Optional<TokenRouteDirectory.Route> route = routes.resolve(secret.prefix());
        if (route.isEmpty()) {
            // hash fictício: o tempo de resposta não revela se o prefixo existe
            hashing.hasher().hash(secret);
            return reject(new Rejected("unknown", 401, null, secret.displayPrefix(), null, null), null);
        }
        UUID tenantId = route.get().tenantId();
        Instant now = Instant.now(clock);
        Result result = tenantExecution.callAsReadOnly(Actor.system(tenantId, "publicapi.authentication"),
                () -> verify(tenantId, route.get().tokenId(), secret, clientIp, now));
        if (result instanceof Rejected rejected) {
            return reject(rejected, route.get().tokenId());
        }
        meterRegistry.counter("publicapi.auth", "outcome", "accepted").increment();
        return result;
    }

    private Result verify(UUID tenantId, UUID tokenId, TokenSecret secret, String clientIp, Instant now) {
        IntegrationToken token = tokens.findById(tokenId).orElse(null);
        if (token == null || !token.matches(secret, hashing.hasher(), now)) {
            return new Rejected(token == null ? "unknown" : "secret_mismatch", 401, tenantId, secret.displayPrefix(),
                    null, null);
        }
        // a partir daqui o segredo confere: a recusa é tráfego do token (medida, nunca cobrada)
        UUID creditorId = token.getCreditorId();
        TokenState state = token.stateAt(now);
        if (state == TokenState.REVOKED || state == TokenState.EXPIRED) {
            return new Rejected(state == TokenState.REVOKED ? "revoked" : "expired", 401, tenantId,
                    secret.displayPrefix(), tokenId, creditorId);
        }
        if (state == TokenState.PAUSED) {
            return new Rejected(PAUSED, 403, tenantId, secret.displayPrefix(), tokenId, creditorId);
        }
        if (tenants.findById(tenantId).filter(t -> t.active()).isEmpty()) {
            return new Rejected("tenant_inactive", 401, tenantId, secret.displayPrefix(), tokenId, creditorId);
        }
        try {
            creditors.requireActive(creditorId);
        } catch (DomainException e) {
            return new Rejected("creditor_inactive", 403, tenantId, secret.displayPrefix(), tokenId, creditorId);
        }
        if (!addressRules.allows(token.getAllowedIps(), clientIp)) {
            return new Rejected("ip_not_allowed", 403, tenantId, secret.displayPrefix(), tokenId, creditorId);
        }
        Actor actor = new Actor(Actor.ActorType.INTEGRATION, token.getId(), tenantId, null,
                "integration:" + token.getDisplayPrefix(), TokenScope.permissionsOf(token.getScopes()),
                CreditorScope.of(List.of(creditorId)));
        return new Authenticated(actor, TokenScope.codesOf(token.getScopes()), token.getId(), token.getDisplayPrefix(),
                creditorId, token.getRateLimitPerMinute());
    }

    /** @param routedTokenId token apontado pelo prefixo (mesmo sem segredo conferido), para o log de acesso */
    private Rejected reject(Rejected rejected, UUID routedTokenId) {
        meterRegistry.counter("publicapi.auth", "outcome", rejected.reason()).increment();
        accessLogger.log(new AccessEvent(AccessEventType.INTEGRATION_TOKEN_REJECTED, false, rejected.tenantId(),
                "INTEGRATION", routedTokenId, null, rejected.prefix(), Map.of("reason", rejected.reason())));
        return rejected;
    }

    @Override
    public RateDecision acquire(UUID tokenId, int limitPerMinute) {
        return rateLimiter.acquire(tokenId, limitPerMinute);
    }
}
