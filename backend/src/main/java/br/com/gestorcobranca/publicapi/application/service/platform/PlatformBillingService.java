package br.com.gestorcobranca.publicapi.application.service.platform;

import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantSwitch;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Casos de uso da equipe da plataforma. Cada operação confere a permissão da plataforma, confere que quem chama é do
 * tenant da plataforma (defesa em profundidade: as permissões {@link Permission#PLATFORM_ONLY} só existem no perfil
 * {@code PLATFORM_ADMIN} desse tenant) e executa o {@link PlatformBillingEngine} <strong>no tenant da assessoria</strong>
 * por {@link TenantSwitch} — dentro da mesma transação e sob RLS, o mesmo mecanismo do provisionamento e do reenvio de
 * convite; não há leitura entre tenants sem RLS. Alterações vão às duas trilhas de auditoria (a da assessoria, com o
 * usuário da plataforma em {@code requestedByPlatformUserId}, e a da plataforma); consultas a dados da assessoria vão
 * ao log de acesso da plataforma ({@code PLATFORM_TENANT_DATA_VIEWED}).
 */
@Service
@Transactional
class PlatformBillingService implements PlatformBillingUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformBillingService.class);
    private static final String PROCESS = "publicapi.platform_billing";

    private final PlatformBillingEngine engine;
    private final TenantSwitch tenantSwitch;
    private final TenantAdministration tenantAdministration;
    private final TenantDirectory tenantDirectory;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    PlatformBillingService(PlatformBillingEngine engine, TenantSwitch tenantSwitch,
                           TenantAdministration tenantAdministration, TenantDirectory tenantDirectory,
                           AccessScope accessScope, CurrentActor currentActor, AuditTrail auditTrail,
                           AccessLogger accessLogger, MeterRegistry meterRegistry, Clock clock) {
        this.engine = engine;
        this.tenantSwitch = tenantSwitch;
        this.tenantAdministration = tenantAdministration;
        this.tenantDirectory = tenantDirectory;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- consolidado

    @Override
    @Transactional(readOnly = true)
    public OverviewView overview() {
        Actor actor = requirePlatform(Permission.PLATFORM_BILLING_VIEW);
        Instant started = Instant.now(clock);
        List<TenantView> tenants = tenantDirectory.activeTenantIds().stream()
                .map(tenantAdministration::findById).flatMap(java.util.Optional::stream)
                .filter(t -> !t.platform()).toList();
        List<Row> rows = new ArrayList<>();
        for (TenantView t : tenants) {
            rows.add(new Row(t, inTenant(t.id(), () -> engine.position(false))));
        }
        rows.sort(Comparator.comparingLong((Row r) -> r.position().billableRequests()).reversed()
                .thenComparing(r -> r.position().amountSoFar(), Comparator.reverseOrder())
                .thenComparing(r -> r.tenant().tradeName()));
        List<TenantSummary> summaries = new ArrayList<>();
        BigDecimal soFar = BigDecimal.ZERO.setScale(2);
        BigDecimal projected = BigDecimal.ZERO.setScale(2);
        BigDecimal lastClosed = BigDecimal.ZERO.setScale(2);
        long requests = 0;
        long mbIn = 0;
        long mbOut = 0;
        long records = 0;
        int withPolicy = 0;
        for (int i = 0; i < rows.size(); i++) {
            TenantView t = rows.get(i).tenant();
            PositionView p = rows.get(i).position();
            summaries.add(new TenantSummary(t.id(), t.slug(), t.tradeName(), p.hasPolicy(), p.periodStart(),
                    p.periodEnd(), p.daysElapsed(), p.cycleDays(), p.policyVersion(), p.billableRequests(),
                    p.billableMbIn(), p.billableMbOut(), p.records(), p.tokens(), p.amountSoFar(),
                    p.projection().amount(), p.lastStatement(), i + 1));
            soFar = soFar.add(p.amountSoFar());
            projected = projected.add(p.projection().amount());
            lastClosed = lastClosed.add(p.lastStatement() == null ? BigDecimal.ZERO : p.lastStatement().totalAmount());
            requests += p.billableRequests();
            mbIn += p.billableMbIn();
            mbOut += p.billableMbOut();
            records += p.records();
            withPolicy += p.hasPolicy() ? 1 : 0;
        }
        long millis = Duration.between(started, Instant.now(clock)).toMillis();
        Timer.builder("publicapi.platform_billing.overview.duration").register(meterRegistry)
                .record(Duration.ofMillis(millis));
        LOG.atDebug().addKeyValue("tenants", rows.size()).addKeyValue("durationMs", millis)
                .log("Consolidado do faturamento da plataforma calculado");
        accessLogger.log(AccessEvent.of(AccessEventType.PLATFORM_TENANT_DATA_VIEWED, actor, Map.of(
                "resource", "platform_billing_overview", "tenants", String.valueOf(rows.size()))));
        return new OverviewView(LocalDate.ofInstant(started, PlatformBillingEngine.ZONE),
                new OverviewTotals(rows.size(), withPolicy, rows.size() - withPolicy, soFar, projected, lastClosed,
                        requests, mbIn, mbOut, records), List.copyOf(summaries), millis);
    }

    private record Row(TenantView tenant, PositionView position) {
    }

    // ---------------------------------------------------------------- política

    @Override
    @Transactional(readOnly = true)
    public PolicyView policy(UUID tenantId) {
        requirePlatform(Permission.PLATFORM_BILLING_VIEW);
        target(tenantId);
        return inTenant(tenantId, engine::policyView);
    }

    @Override
    public PolicyView createPolicy(UUID tenantId, CreatePolicyCommand command) {
        Actor actor = requirePlatform(Permission.PLATFORM_BILLING_MANAGE);
        TenantView tenant = target(tenantId);
        PolicyView view = inTenant(tenantId, () -> engine.createPolicy(command, actor.id()));
        platformAudit("platform_billing.policy_created", tenant, command.reason(), Map.<String, Object>of(
                "policyId", view.policyId(), "startDate", String.valueOf(view.startDate()),
                "endDate", String.valueOf(view.endDate()), "version", 1));
        LOG.info("Política de cobrança da plataforma criada para a assessoria {}", tenantId);
        return view;
    }

    @Override
    public PolicyView addVersion(UUID tenantId, PolicyTermsCommand terms, String reason) {
        Actor actor = requirePlatform(Permission.PLATFORM_BILLING_MANAGE);
        TenantView tenant = target(tenantId);
        PolicyView view = inTenant(tenantId, () -> engine.addVersion(terms, reason, actor.id()));
        platformAudit("platform_billing.policy_version_added", tenant, reason, Map.<String, Object>of(
                "policyId", view.policyId(), "version", view.versions().getFirst().version()));
        return view;
    }

    @Override
    public PolicyView changeTerm(UUID tenantId, LocalDate startDate, LocalDate endDate, String reason) {
        Actor actor = requirePlatform(Permission.PLATFORM_BILLING_MANAGE);
        TenantView tenant = target(tenantId);
        PolicyView view = inTenant(tenantId, () -> engine.changeTerm(startDate, endDate, reason, actor.id()));
        platformAudit("platform_billing.policy_term_changed", tenant, reason, Map.<String, Object>of(
                "policyId", view.policyId(), "startDate", String.valueOf(view.startDate()),
                "endDate", String.valueOf(view.endDate())));
        return view;
    }

    // ---------------------------------------------------------------- posição e extratos

    @Override
    @Transactional(readOnly = true)
    public PositionView position(UUID tenantId) {
        Actor actor = requirePlatform(Permission.PLATFORM_BILLING_VIEW);
        target(tenantId);
        PositionView view = inTenant(tenantId, () -> engine.position(true));
        viewed(actor, tenantId, "platform_billing_position", null);
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public StatementView preview(UUID tenantId) {
        Actor actor = requirePlatform(Permission.PLATFORM_BILLING_VIEW);
        target(tenantId);
        StatementView view = inTenant(tenantId, engine::preview);
        viewed(actor, tenantId, "platform_statement_preview", null);
        return view;
    }

    @Override
    public StatementView close(UUID tenantId, LocalDate periodStart, String reason) {
        Actor actor = requirePlatform(Permission.PLATFORM_BILLING_MANAGE);
        TenantView tenant = target(tenantId);
        StatementView s = inTenant(tenantId, () -> engine.close(periodStart, reason, actor.id()));
        platformAudit(s.revision() == 1 ? "platform_statement.closed" : "platform_statement.reclosed", tenant,
                s.reason(), Map.<String, Object>of("statementId", s.id(), "period", s.periodStart() + "/" + s.periodEnd(),
                        "revision", s.revision(), "totalAmount", s.totalAmount(), "usageHash", s.usageHash()));
        LOG.atInfo().addKeyValue("tenantId", tenantId).addKeyValue("revision", s.revision())
                .log("Extrato da plataforma fechado manualmente");
        return s;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<StatementView> statements(UUID tenantId, YearMonth month, PageQuery page) {
        Actor actor = requirePlatform(Permission.PLATFORM_BILLING_VIEW);
        target(tenantId);
        PageResponse<StatementView> result = inTenant(tenantId, () -> engine.statements(month, page));
        viewed(actor, tenantId, "platform_statement_list", null);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public StatementView statement(UUID tenantId, UUID statementId) {
        Actor actor = requirePlatform(Permission.PLATFORM_BILLING_VIEW);
        target(tenantId);
        StatementView s = inTenant(tenantId, () -> engine.statement(statementId));
        viewed(actor, tenantId, "platform_statement", statementId);
        return s;
    }

    @Override
    @Transactional(readOnly = true)
    public void exportStatement(UUID tenantId, UUID statementId, OutputStream output) {
        Actor actor = requirePlatform(Permission.PLATFORM_BILLING_VIEW);
        target(tenantId);
        StatementView s = inTenant(tenantId, () -> engine.statement(statementId));
        accessLogger.log(AccessEvent.of(AccessEventType.DATA_EXPORTED, actor, Map.of("resource", "platform_statement",
                "resourceId", statementId.toString(), "targetTenantId", tenantId.toString(),
                "usageHash", s.usageHash())));
        inTenant(tenantId, () -> engine.export(statementId, output));
    }

    // ---------------------------------------------------------------- apoio

    private Actor requirePlatform(Permission permission) {
        accessScope.requirePermission(permission);
        Actor actor = currentActor.get();
        boolean platform = tenantAdministration.findById(actor.tenantId()).map(TenantView::platform).orElse(false);
        if (!platform) {
            accessLogger.log(AccessEvent.of(AccessEventType.ACCESS_DENIED, actor, Map.of(
                    "permission", permission.name(), "reason", "platform_tenant_required")));
            throw new ScopeAccessDeniedException();
        }
        return actor;
    }

    /** Assessoria cobrada: existente e diferente do tenant da plataforma (404 nos dois casos). */
    private TenantView target(UUID tenantId) {
        if (tenantId == null) {
            throw new ResourceNotFoundException("tenant", "null");
        }
        return tenantAdministration.findById(tenantId).filter(t -> !t.platform())
                .orElseThrow(() -> ResourceNotFoundException.of("tenant", tenantId));
    }

    private <T> T inTenant(UUID tenantId, Supplier<T> action) {
        return tenantSwitch.callAsSystemOf(tenantId, PROCESS, action);
    }

    private void viewed(Actor actor, UUID tenantId, String resource, UUID resourceId) {
        Map<String, String> details = new HashMap<>();
        details.put("resource", resource);
        details.put("targetTenantId", tenantId.toString());
        if (resourceId != null) {
            details.put("resourceId", resourceId.toString());
        }
        accessLogger.log(AccessEvent.of(AccessEventType.PLATFORM_TENANT_DATA_VIEWED, actor, details));
    }

    /** Trilha da plataforma: quem alterou, em qual assessoria e por quê. */
    private void platformAudit(String action, TenantView tenant, String reason, Map<String, Object> meta) {
        AuditEntry entry = AuditEntry.of(action, "tenant", tenant.id()).reason(reason == null || reason.isBlank()
                ? null : reason.strip());
        meta.forEach(entry::meta);
        auditTrail.record(entry.meta("targetTenantId", tenant.id()));
    }
}
