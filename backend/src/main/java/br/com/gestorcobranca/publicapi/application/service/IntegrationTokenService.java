package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.publicapi.application.PublicApiProperties;
import br.com.gestorcobranca.publicapi.application.port.in.IntegrationTokenUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.IssueIntegrationToken;
import br.com.gestorcobranca.publicapi.application.port.out.TokenRouteDirectory;
import br.com.gestorcobranca.publicapi.domain.model.IntegrationToken;
import br.com.gestorcobranca.publicapi.domain.model.TokenScope;
import br.com.gestorcobranca.publicapi.domain.model.TokenSecret;
import br.com.gestorcobranca.publicapi.domain.model.TokenState;
import br.com.gestorcobranca.publicapi.domain.model.TokenStatus;
import br.com.gestorcobranca.publicapi.domain.repository.IntegrationTokenRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Criação, consulta, alteração, revogação e rotação dos tokens de integração. Só o hash do segredo é gravado; o valor
 * completo sai uma única vez no retorno da criação ou da rotação. Toda mudança é auditada na mesma transação, vai ao
 * log de acesso (rastreabilidade de credenciais) e gera evento de negócio.
 */
@Service
@Transactional
class IntegrationTokenService implements IntegrationTokenUseCases, IssueIntegrationToken {

    private final IntegrationTokenRepository tokens;
    private final TokenRouteDirectory routes;
    private final CreditorUseCases creditors;
    private final TokenHashing hashing;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final BusinessEventLogger businessEvents;
    private final MeterRegistry meterRegistry;
    private final PublicApiProperties properties;
    private final Clock clock;

    IntegrationTokenService(IntegrationTokenRepository tokens, TokenRouteDirectory routes, CreditorUseCases creditors,
                            TokenHashing hashing, AccessScope accessScope, CurrentActor currentActor,
                            AuditTrail auditTrail, AccessLogger accessLogger, BusinessEventLogger businessEvents,
                            MeterRegistry meterRegistry, PublicApiProperties properties, Clock clock) {
        this.tokens = tokens;
        this.routes = routes;
        this.creditors = creditors;
        this.hashing = hashing;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.businessEvents = businessEvents;
        this.meterRegistry = meterRegistry;
        this.properties = properties;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- criação

    @Override
    public IssuedToken issue(IssueTokenCommand command) {
        accessScope.requirePermission(Permission.PUBLIC_API_TOKEN_MANAGE);
        Guard.require(command != null && command.creditorId() != null, "integration_token.creditor_required",
                "Credor obrigatório");
        accessScope.requireCreditor(command.creditorId());
        creditors.requireActive(command.creditorId());
        Instant now = Instant.now(clock);
        Set<TokenScope> scopes = TokenScope.parse(command.scopes());
        int rateLimit = command.rateLimitPerMinute() == null ? properties.defaultRateLimitPerMinute()
                : command.rateLimitPerMinute();
        List<String> ips = IpAllowList.normalize(command.allowedIps());
        TokenSecret secret = TokenSecret.generate(hashing.random());
        IntegrationToken token = tokens.save(new IntegrationToken(command.creditorId(), command.name(),
                command.description(), secret.prefix(), hashing.hasher().hash(secret), hashing.hasher().activeKeyId(),
                scopes, rateLimit, ips, command.expiresAt(), now));
        routes.register(secret.prefix(), currentActor.tenantId(), token.getId());

        auditTrail.record(AuditEntry.of("integration_token.created", "integration_token", token.getId())
                .creditor(token.getCreditorId())
                .created("name", token.getName())
                .created("prefix", token.getDisplayPrefix())
                .created("scopes", String.join(",", TokenScope.codesOf(scopes)))
                .created("rateLimitPerMinute", rateLimit)
                .created("allowedIps", String.join(",", ips))
                .created("expiresAt", token.getExpiresAt()));
        accessLogger.log(AccessEvent.of(AccessEventType.INTEGRATION_TOKEN_CREATED, currentActor.get(),
                Map.of("tokenId", token.getId().toString(), "prefix", token.getDisplayPrefix(),
                        "creditorId", token.getCreditorId().toString(),
                        "scopes", String.join(",", TokenScope.codesOf(scopes)))));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PUBLIC_API_TOKEN_CREATED)
                .creditor(token.getCreditorId()).reference("integration_token", token.getId())
                .attr("scopes", String.join(",", TokenScope.codesOf(scopes))).attr("rateLimitPerMinute", rateLimit)
                .attr("ipRestricted", !ips.isEmpty()).attr("expires", token.getExpiresAt() != null));
        meterRegistry.counter("publicapi.tokens.lifecycle", "operation", "created").increment();
        return new IssuedToken(TokenViews.view(token, now), secret.value());
    }

    // ---------------------------------------------------------------- consulta

    @Override
    @Transactional(readOnly = true)
    public TokenView get(UUID tokenId) {
        accessScope.requirePermission(Permission.PUBLIC_API_TOKEN_VIEW);
        return TokenViews.view(loadVisible(tokenId), Instant.now(clock));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TokenView> search(UUID creditorId, String status, PageQuery page) {
        accessScope.requirePermission(Permission.PUBLIC_API_TOKEN_VIEW);
        if (creditorId != null) {
            accessScope.requireVisible("creditor", creditorId, creditorId);
        }
        Instant now = Instant.now(clock);
        TokenState state = parseState(status);
        Set<UUID> allowed = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(null);
        TokenStatus stored = state == null ? null : state == TokenState.REVOKED ? TokenStatus.REVOKED : TokenStatus.ACTIVE;
        PageResponse<TokenView> result = PageResponse.of(tokens.search(allowed, creditorId, stored,
                page.toPageable(Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")))),
                t -> TokenViews.view(t, now));
        if (state == null || state == TokenState.REVOKED) {
            return result;
        }
        // ACTIVE × EXPIRED dependem do instante: filtra a página (expirados são raros e a página é pequena)
        List<TokenView> filtered = result.content().stream().filter(v -> v.state().equals(state.name())).toList();
        return new PageResponse<>(filtered, result.page(), result.size(), result.totalElements(), result.totalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScopeView> scopes() {
        return Arrays.stream(TokenScope.values()).map(s -> new ScopeView(s.code(), s.description())).toList();
    }

    // ---------------------------------------------------------------- comandos

    @Override
    public TokenView updateSettings(UUID tokenId, UpdateTokenCommand command) {
        accessScope.requirePermission(Permission.PUBLIC_API_TOKEN_MANAGE);
        Guard.require(command != null, "integration_token.required", "Dados obrigatórios");
        IntegrationToken token = loadForCommand(tokenId);
        List<String> ips = IpAllowList.normalize(command.allowedIps());
        AuditEntry audit = AuditEntry.of("integration_token.updated", "integration_token", tokenId)
                .creditor(token.getCreditorId())
                .change("name", token.getName(), command.name() == null ? null : command.name().strip())
                .change("rateLimitPerMinute", token.getRateLimitPerMinute(), command.rateLimitPerMinute())
                .change("allowedIps", String.join(",", token.getAllowedIps()), String.join(",", ips));
        token.updateSettings(command.name(), command.description(), command.rateLimitPerMinute(), ips);
        tokens.save(token);
        auditTrail.record(audit);
        accessLogger.log(AccessEvent.of(AccessEventType.PERMISSION_CHANGED, currentActor.get(),
                Map.of("resource", "integration_token", "tokenId", tokenId.toString(), "prefix", token.getDisplayPrefix(),
                        "rateLimitPerMinute", String.valueOf(token.getRateLimitPerMinute()),
                        "allowedIps", String.valueOf(ips.size()))));
        return TokenViews.view(token, Instant.now(clock));
    }

    @Override
    public TokenView revoke(UUID tokenId, String reason) {
        accessScope.requirePermission(Permission.PUBLIC_API_TOKEN_MANAGE);
        IntegrationToken token = loadForCommand(tokenId);
        Instant now = Instant.now(clock);
        TokenState previous = token.stateAt(now);
        token.revoke(currentActor.actorId(), reason, now);
        tokens.save(token);
        auditTrail.record(AuditEntry.of("integration_token.revoked", "integration_token", tokenId)
                .creditor(token.getCreditorId()).reason(token.getRevokeReason())
                .change("state", previous, TokenState.REVOKED).meta("prefix", token.getDisplayPrefix()));
        accessLogger.log(AccessEvent.of(AccessEventType.INTEGRATION_TOKEN_REVOKED, currentActor.get(),
                Map.of("tokenId", tokenId.toString(), "prefix", token.getDisplayPrefix(),
                        "creditorId", token.getCreditorId().toString())));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PUBLIC_API_TOKEN_REVOKED)
                .creditor(token.getCreditorId()).reference("integration_token", tokenId)
                .attr("previousState", previous)
                .attr("ageDays", Duration.between(token.getCreatedAt(), now).toDays()));
        meterRegistry.counter("publicapi.tokens.lifecycle", "operation", "revoked").increment();
        return TokenViews.view(token, now);
    }

    @Override
    public IssuedToken rotate(UUID tokenId, int graceMinutes) {
        accessScope.requirePermission(Permission.PUBLIC_API_TOKEN_MANAGE);
        if (graceMinutes < 0 || graceMinutes > IntegrationToken.MAX_ROTATION_GRACE.toMinutes()) {
            throw new BusinessRuleException("integration_token.grace_invalid", "Carência entre 0 e 1440 minutos");
        }
        IntegrationToken token = loadForCommand(tokenId);
        Instant now = Instant.now(clock);
        TokenSecret secret = new TokenSecret(token.getPrefix(), "").rotate(hashing.random());
        token.rotate(hashing.hasher().hash(secret), hashing.hasher().activeKeyId(), Duration.ofMinutes(graceMinutes), now);
        tokens.save(token);
        auditTrail.record(AuditEntry.of("integration_token.rotated", "integration_token", tokenId)
                .creditor(token.getCreditorId())
                .change("rotationCount", token.getRotationCount() - 1, token.getRotationCount())
                .meta("prefix", token.getDisplayPrefix()).meta("graceMinutes", graceMinutes));
        accessLogger.log(AccessEvent.of(AccessEventType.INTEGRATION_TOKEN_ROTATED, currentActor.get(),
                Map.of("tokenId", tokenId.toString(), "prefix", token.getDisplayPrefix(),
                        "graceMinutes", String.valueOf(graceMinutes))));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PUBLIC_API_TOKEN_ROTATED)
                .creditor(token.getCreditorId()).reference("integration_token", tokenId)
                .attr("graceMinutes", graceMinutes).attr("rotationCount", token.getRotationCount()));
        meterRegistry.counter("publicapi.tokens.lifecycle", "operation", "rotated").increment();
        return new IssuedToken(TokenViews.view(token, now), secret.value());
    }

    // ---------------------------------------------------------------- escopo

    private IntegrationToken loadVisible(UUID tokenId) {
        IntegrationToken token = tokens.findById(tokenId)
                .orElseThrow(() -> ResourceNotFoundException.of("integration_token", tokenId));
        accessScope.requireVisible("integration_token", tokenId, token.getCreditorId());
        return token;
    }

    private IntegrationToken loadForCommand(UUID tokenId) {
        IntegrationToken token = loadVisible(tokenId);
        accessScope.requireCreditor(token.getCreditorId());
        return token;
    }

    private static TokenState parseState(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return TokenState.valueOf(status.strip());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("integration_token.status_invalid", "Situação inválida");
        }
    }
}
