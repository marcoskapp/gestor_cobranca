package br.com.gestorcobranca.publicapi.application.service.platform;

import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorView;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.TrafficRow;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.TrafficTotals;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformTokenControlUseCases;
import br.com.gestorcobranca.publicapi.domain.model.IntegrationToken;
import br.com.gestorcobranca.publicapi.domain.model.TokenScope;
import br.com.gestorcobranca.publicapi.domain.model.TokenState;
import br.com.gestorcobranca.publicapi.domain.model.TokenStatus;
import br.com.gestorcobranca.publicapi.domain.repository.IntegrationTokenRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.publicapi.PlatformTokensControlled;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pausa, retomada e revogação de tokens de integração pela equipe da plataforma (decisões 763–772). Confere a permissão
 * e o tenant da plataforma ({@link PlatformAccess}), troca o tenant da transação para a assessoria dona do token
 * ({@code TenantSwitch}, sob RLS) e, na mesma transação:
 * <ol>
 *   <li>aplica a transição no token (regras no {@link IntegrationToken});</li>
 *   <li>grava a auditoria da assessoria (com {@code requestedByPlatformUserId} e o motivo) e o evento de negócio
 *       ({@code public_api.token_paused|token_resumed|token_revoked}, com {@code byPlatform});</li>
 *   <li>publica {@link PlatformTokensControlled} — o {@code identity} avisa por e-mail, depois do commit, quem
 *       administra os tokens na assessoria;</li>
 * </ol>
 * e, de volta ao tenant da plataforma, grava a trilha da plataforma e o log de acesso
 * ({@code INTEGRATION_TOKEN_PAUSED|RESUMED|REVOKED}, com quem fez, motivo e assessoria).
 */
@Service
@Transactional
class PlatformTokenControlService implements PlatformTokenControlUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformTokenControlService.class);
    private static final String PROCESS = "publicapi.platform_token_control";
    private static final int REASON_LOG_LIMIT = 500;

    private final PlatformAccess access;
    private final PlatformBillingEngine engine;
    private final IntegrationTokenRepository tokens;
    private final CreditorUseCases creditors;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final BusinessEventLogger businessEvents;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    PlatformTokenControlService(PlatformAccess access, PlatformBillingEngine engine, IntegrationTokenRepository tokens,
                                CreditorUseCases creditors, AuditTrail auditTrail, AccessLogger accessLogger,
                                BusinessEventLogger businessEvents, ApplicationEventPublisher events,
                                MeterRegistry meterRegistry, Clock clock) {
        this.access = access;
        this.engine = engine;
        this.tokens = tokens;
        this.creditors = creditors;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.businessEvents = businessEvents;
        this.events = events;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- consulta

    @Override
    @Transactional(readOnly = true)
    public List<PlatformTokenView> tokens(UUID tenantId) {
        Actor actor = access.requirePlatform(Permission.PLATFORM_BILLING_VIEW);
        access.target(tenantId);
        List<PlatformTokenView> list = access.inTenant(tenantId, PROCESS, () -> views(tokens.findAll()));
        access.viewed(actor, tenantId, "platform_api_tokens", null);
        return list;
    }

    // ---------------------------------------------------------------- comandos de um token

    @Override
    public PlatformTokenView pause(UUID tenantId, UUID tokenId, String reason, Instant resumeAt) {
        Actor actor = access.requirePlatform(Permission.PLATFORM_API_TOKENS_MANAGE);
        TenantView tenant = access.target(tenantId);
        Instant now = Instant.now(clock);
        PlatformTokenView view = access.inTenant(tenantId, PROCESS, () -> {
            IntegrationToken token = load(tokenId);
            pauseInTenant(token, actor, reason, resumeAt, now);
            publish(tenantId, PlatformTokensControlled.PAUSED, List.of(token), resumeAt, now);
            return views(List.of(token)).getFirst();
        });
        platformRecord(AccessEventType.INTEGRATION_TOKEN_PAUSED, "platform_api_token.paused", actor, tenant, reason,
                view, resumeAt);
        return view;
    }

    @Override
    public PlatformTokenView resume(UUID tenantId, UUID tokenId, String reason) {
        Actor actor = access.requirePlatform(Permission.PLATFORM_API_TOKENS_MANAGE);
        TenantView tenant = access.target(tenantId);
        Instant now = Instant.now(clock);
        PlatformTokenView view = access.inTenant(tenantId, PROCESS, () -> {
            IntegrationToken token = load(tokenId);
            resumeInTenant(token, actor, reason, now);
            publish(tenantId, PlatformTokensControlled.RESUMED, List.of(token), null, now);
            return views(List.of(token)).getFirst();
        });
        platformRecord(AccessEventType.INTEGRATION_TOKEN_RESUMED, "platform_api_token.resumed", actor, tenant, reason,
                view, null);
        return view;
    }

    @Override
    public PlatformTokenView revoke(UUID tenantId, UUID tokenId, String reason) {
        Actor actor = access.requirePlatform(Permission.PLATFORM_API_TOKENS_MANAGE);
        TenantView tenant = access.target(tenantId);
        Instant now = Instant.now(clock);
        PlatformTokenView view = access.inTenant(tenantId, PROCESS, () -> {
            IntegrationToken token = load(tokenId);
            TokenState previous = token.stateAt(now);
            token.revokeByPlatform(actor.id(), reason, now);
            tokens.save(token);
            auditTrail.record(AuditEntry.of("integration_token.revoked", "integration_token", token.getId())
                    .creditor(token.getCreditorId()).reason(token.getRevokeReason())
                    .change("state", previous, TokenState.REVOKED).meta("prefix", token.getDisplayPrefix())
                    .meta("byPlatform", true).meta("requestedByPlatformUserId", actor.id()));
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.PUBLIC_API_TOKEN_REVOKED)
                    .creditor(token.getCreditorId()).reference("integration_token", token.getId())
                    .attr("previousState", previous).attr("byPlatform", true)
                    .attr("ageDays", Duration.between(token.getCreatedAt(), now).toDays()));
            meterRegistry.counter("publicapi.tokens.lifecycle", "operation", "revoked_by_platform").increment();
            publish(tenantId, PlatformTokensControlled.REVOKED, List.of(token), null, now);
            return views(List.of(token)).getFirst();
        });
        platformRecord(AccessEventType.INTEGRATION_TOKEN_REVOKED, "platform_api_token.revoked", actor, tenant, reason,
                view, null);
        return view;
    }

    // ---------------------------------------------------------------- comandos em massa

    @Override
    public BulkResult pauseAll(UUID tenantId, String reason, Instant resumeAt) {
        Actor actor = access.requirePlatform(Permission.PLATFORM_API_TOKENS_MANAGE);
        TenantView tenant = access.target(tenantId);
        requireReason(reason);
        Instant now = Instant.now(clock);
        BulkResult result = access.inTenant(tenantId, PROCESS, () -> {
            List<IntegrationToken> all = tokens.findAll();
            List<IntegrationToken> targets = all.stream().filter(t -> t.stateAt(now) == TokenState.ACTIVE)
                    .sorted(Comparator.comparing(IntegrationToken::getId)).toList();
            targets.forEach(t -> pauseInTenant(t, actor, reason, resumeAt, now));
            publish(tenantId, PlatformTokensControlled.PAUSED, targets, resumeAt, now);
            return new BulkResult(targets.size(), all.size() - targets.size(), views(all));
        });
        bulkRecord(AccessEventType.INTEGRATION_TOKEN_PAUSED, "platform_api_token.paused_all", actor, tenant, reason,
                result, resumeAt);
        return result;
    }

    @Override
    public BulkResult resumeAll(UUID tenantId, String reason) {
        Actor actor = access.requirePlatform(Permission.PLATFORM_API_TOKENS_MANAGE);
        TenantView tenant = access.target(tenantId);
        requireReason(reason);
        Instant now = Instant.now(clock);
        BulkResult result = access.inTenant(tenantId, PROCESS, () -> {
            List<IntegrationToken> all = tokens.findAll();
            List<IntegrationToken> targets = all.stream().filter(t -> t.getStatus() == TokenStatus.PAUSED
                            && t.stateAt(now) != TokenState.EXPIRED)
                    .sorted(Comparator.comparing(IntegrationToken::getId)).toList();
            targets.forEach(t -> resumeInTenant(t, actor, reason, now));
            publish(tenantId, PlatformTokensControlled.RESUMED, targets, null, now);
            return new BulkResult(targets.size(), all.size() - targets.size(), views(all));
        });
        bulkRecord(AccessEventType.INTEGRATION_TOKEN_RESUMED, "platform_api_token.resumed_all", actor, tenant, reason,
                result, null);
        return result;
    }

    // ---------------------------------------------------------------- no tenant da assessoria

    private void pauseInTenant(IntegrationToken token, Actor actor, String reason, Instant resumeAt, Instant now) {
        token.pauseByPlatform(actor.id(), reason, resumeAt, now);
        tokens.save(token);
        auditTrail.record(AuditEntry.of("integration_token.paused", "integration_token", token.getId())
                .creditor(token.getCreditorId()).reason(token.getPauseReason())
                .change("state", TokenState.ACTIVE, TokenState.PAUSED).created("resumeAt", resumeAt)
                .meta("prefix", token.getDisplayPrefix()).meta("byPlatform", true)
                .meta("requestedByPlatformUserId", actor.id()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PUBLIC_API_TOKEN_PAUSED)
                .creditor(token.getCreditorId()).reference("integration_token", token.getId())
                .attr("byPlatform", true).attr("scheduledResume", resumeAt != null)
                .attr("scheduledHours", resumeAt == null ? null : Duration.between(now, resumeAt).toHours()));
        meterRegistry.counter("publicapi.tokens.lifecycle", "operation", "paused_by_platform").increment();
    }

    private void resumeInTenant(IntegrationToken token, Actor actor, String reason, Instant now) {
        requireReason(reason);
        Instant pausedAt = token.getPausedAt();
        boolean wasDue = token.resumeDue(now);
        token.resume();
        tokens.save(token);
        auditTrail.record(AuditEntry.of("integration_token.resumed", "integration_token", token.getId())
                .creditor(token.getCreditorId()).reason(reason.strip())
                .change("state", wasDue ? TokenState.ACTIVE : TokenState.PAUSED, TokenState.ACTIVE)
                .meta("prefix", token.getDisplayPrefix()).meta("byPlatform", true).meta("trigger", "MANUAL")
                .meta("requestedByPlatformUserId", actor.id()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PUBLIC_API_TOKEN_RESUMED)
                .creditor(token.getCreditorId()).reference("integration_token", token.getId())
                .attr("byPlatform", true).attr("trigger", "MANUAL")
                .attr("pausedHours", pausedAt == null ? null : Duration.between(pausedAt, now).toHours()));
        meterRegistry.counter("publicapi.tokens.lifecycle", "operation", "resumed_by_platform").increment();
    }

    private IntegrationToken load(UUID tokenId) {
        // sob RLS da assessoria: token de outra assessoria não existe aqui (404)
        return tokens.findById(tokenId).orElseThrow(() -> ResourceNotFoundException.of("integration_token", tokenId));
    }

    private void publish(UUID tenantId, String action, List<IntegrationToken> affected, Instant resumeAt, Instant now) {
        if (affected.isEmpty()) {
            return;
        }
        events.publishEvent(new PlatformTokensControlled(Ids.newId(), tenantId, now, action,
                affected.stream().map(IntegrationToken::getId).toList(),
                affected.stream().map(IntegrationToken::getDisplayPrefix).toList(), resumeAt));
    }

    /** Tokens com o credor e o tráfego do ciclo em andamento: ativos e pausados primeiro, depois por nome. */
    private List<PlatformTokenView> views(List<IntegrationToken> list) {
        Instant now = Instant.now(clock);
        Map<UUID, TrafficTotals> traffic = engine.currentTraffic(false).rows().stream()
                .filter(r -> "TOKEN".equals(r.kind()))
                .collect(Collectors.toMap(r -> UUID.fromString(r.key()), TrafficRow::totals, (a, b) -> a));
        Map<UUID, String> creditorNames = list.isEmpty() ? Map.of() : creditors.getAll(list.stream()
                        .map(IntegrationToken::getCreditorId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(CreditorView::id, CreditorView::tradeName));
        return list.stream()
                .sorted(Comparator.comparingInt((IntegrationToken t) -> order(t.stateAt(now)))
                        .thenComparing(IntegrationToken::getName).thenComparing(IntegrationToken::getId))
                .map(t -> {
                    boolean paused = t.stateAt(now) == TokenState.PAUSED;
                    return new PlatformTokenView(t.getId(), t.getCreditorId(), creditorNames.get(t.getCreditorId()),
                            t.getName(), t.getDisplayPrefix(), TokenScope.codesOf(t.getScopes()), t.stateAt(now).name(),
                            t.getCreatedAt(), t.getExpiresAt(), t.getLastUsedAt(), paused ? t.getPausedAt() : null,
                            paused ? t.getPausedBy() : null, paused ? t.getPauseReason() : null,
                            paused ? t.getResumeAt() : null, t.getRevokedAt(), t.isRevokedByPlatform(),
                            t.getRevokeReason(), traffic.getOrDefault(t.getId(), TrafficTotals.ZERO));
                })
                .toList();
    }

    private static int order(TokenState state) {
        return switch (state) {
            case PAUSED -> 0;
            case ACTIVE -> 1;
            case EXPIRED -> 2;
            case REVOKED -> 3;
        };
    }

    private static void requireReason(String reason) {
        Guard.require(reason != null && !reason.isBlank(), "integration_token.reason_required", "Informe o motivo");
        if (reason.strip().length() > REASON_LOG_LIMIT) {
            throw new BusinessRuleException("integration_token.reason_too_long", "Motivo com até 500 caracteres");
        }
    }

    // ---------------------------------------------------------------- trilha da plataforma

    private void platformRecord(AccessEventType type, String action, Actor actor, TenantView tenant, String reason,
                                PlatformTokenView token, Instant resumeAt) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tokenId", token.id());
        meta.put("prefix", token.prefix());
        meta.put("creditorId", token.creditorId());
        if (resumeAt != null) {
            meta.put("resumeAt", resumeAt.toString());
        }
        access.platformAudit(action, tenant, reason, meta);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("tokenId", token.id().toString());
        details.put("prefix", token.prefix());
        details.put("creditorId", token.creditorId().toString());
        details.put("targetTenantId", tenant.id().toString());
        details.put("byPlatform", "true");
        details.put("reason", reason.strip());
        if (resumeAt != null) {
            details.put("resumeAt", resumeAt.toString());
        }
        accessLogger.log(AccessEvent.of(type, actor, details));
        LOG.atInfo().addKeyValue("tenantId", tenant.id()).addKeyValue("tokenId", token.id())
                .addKeyValue("action", action).log("Token de integração controlado pela plataforma");
    }

    private void bulkRecord(AccessEventType type, String action, Actor actor, TenantView tenant, String reason,
                            BulkResult result, Instant resumeAt) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("affected", result.affected());
        meta.put("skipped", result.skipped());
        if (resumeAt != null) {
            meta.put("resumeAt", resumeAt.toString());
        }
        access.platformAudit(action, tenant, reason, meta);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("targetTenantId", tenant.id().toString());
        details.put("bulk", "true");
        details.put("affected", String.valueOf(result.affected()));
        details.put("byPlatform", "true");
        details.put("reason", reason.strip());
        if (resumeAt != null) {
            details.put("resumeAt", resumeAt.toString());
        }
        accessLogger.log(AccessEvent.of(type, actor, details));
        LOG.atInfo().addKeyValue("tenantId", tenant.id()).addKeyValue("affected", result.affected())
                .addKeyValue("action", action).log("Tokens de integração controlados em massa pela plataforma");
    }
}
