package br.com.gestorcobranca.publicapi.application.service.platform;

import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Casos de uso da equipe da plataforma. Cada operação confere a permissão da plataforma e que quem chama é do tenant da
 * plataforma ({@link PlatformAccess}) e executa o {@link PlatformBillingEngine} <strong>no tenant da assessoria</strong>
 * por {@link br.com.gestorcobranca.shared.tenancy.TenantSwitch} — dentro da mesma transação e sob RLS, o mesmo mecanismo
 * do provisionamento e do reenvio de convite; não há leitura entre tenants sem RLS. Alterações vão às duas trilhas de
 * auditoria (a da assessoria, com o usuário da plataforma em {@code requestedByPlatformUserId}, e a da plataforma);
 * consultas a dados da assessoria vão ao log de acesso da plataforma ({@code PLATFORM_TENANT_DATA_VIEWED}).
 * <p>
 * <b>Assessorias suspensas</b> (decisão 760, revendo a 752): aparecem no consolidado e na posição com o consumo e o
 * tráfego da API (inclusive as chamadas recusadas pela suspensão), com valores monetários zerados; prévia e fechamento
 * são recusados enquanto durar a suspensão (o fechamento automático continua só nas ativas).
 */
@Service
@Transactional
class PlatformBillingService implements PlatformBillingUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformBillingService.class);
    private static final String PROCESS = "publicapi.platform_billing";

    private final PlatformBillingEngine engine;
    private final PlatformAccess access;
    private final TenantAdministration tenantAdministration;
    private final TenantDirectory tenantDirectory;
    private final AccessLogger accessLogger;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    PlatformBillingService(PlatformBillingEngine engine, PlatformAccess access,
                           TenantAdministration tenantAdministration, TenantDirectory tenantDirectory,
                           AccessLogger accessLogger, MeterRegistry meterRegistry, Clock clock) {
        this.engine = engine;
        this.access = access;
        this.tenantAdministration = tenantAdministration;
        this.tenantDirectory = tenantDirectory;
        this.accessLogger = accessLogger;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- consolidado

    @Override
    @Transactional(readOnly = true)
    public OverviewView overview() {
        Actor actor = access.requirePlatform(Permission.PLATFORM_BILLING_VIEW);
        Instant started = Instant.now(clock);
        List<TenantView> tenants = tenantDirectory.allTenantIds().stream()
                .map(tenantAdministration::findById).flatMap(java.util.Optional::stream)
                .filter(t -> !t.platform()).toList();
        List<Row> rows = new ArrayList<>();
        for (TenantView t : tenants) {
            rows.add(inTenant(t.id(), () -> new Row(t, engine.position(false, !t.active()), engine.pausedTokens())));
        }
        rows.sort(Comparator.comparingLong((Row r) -> r.position().billableRequests()).reversed()
                .thenComparing(r -> r.position().amountSoFar(), Comparator.reverseOrder())
                .thenComparing(r -> r.position().traffic().totals().rejectedRequests(), Comparator.reverseOrder())
                .thenComparing(r -> r.tenant().tradeName()));
        List<TenantSummary> summaries = new ArrayList<>();
        BigDecimal soFar = BigDecimal.ZERO.setScale(2);
        BigDecimal projected = BigDecimal.ZERO.setScale(2);
        BigDecimal lastClosed = BigDecimal.ZERO.setScale(2);
        long billable = 0;
        long mbIn = 0;
        long mbOut = 0;
        long records = 0;
        long requests = 0;
        long nonBillable = 0;
        long rejected = 0;
        int withPolicy = 0;
        int suspended = 0;
        int paused = 0;
        for (int i = 0; i < rows.size(); i++) {
            TenantView t = rows.get(i).tenant();
            PositionView p = rows.get(i).position();
            TrafficTotals traffic = p.traffic().totals();
            summaries.add(new TenantSummary(t.id(), t.slug(), t.tradeName(), p.hasPolicy(), p.periodStart(),
                    p.periodEnd(), p.daysElapsed(), p.cycleDays(), p.policyVersion(), p.billableRequests(),
                    p.billableMbIn(), p.billableMbOut(), p.records(), p.tokens(), p.amountSoFar(),
                    p.projection().amount(), p.lastStatement(), i + 1, p.suspended(), traffic.requests(),
                    traffic.nonBillableRequests(), traffic.rejectedRequests(), rows.get(i).pausedTokens()));
            soFar = soFar.add(p.amountSoFar());
            projected = projected.add(p.projection().amount());
            lastClosed = lastClosed.add(p.lastStatement() == null ? BigDecimal.ZERO : p.lastStatement().totalAmount());
            billable += p.billableRequests();
            mbIn += p.billableMbIn();
            mbOut += p.billableMbOut();
            records += p.records();
            requests += traffic.requests();
            nonBillable += traffic.nonBillableRequests();
            rejected += traffic.rejectedRequests();
            withPolicy += p.hasPolicy() ? 1 : 0;
            suspended += p.suspended() ? 1 : 0;
            paused += rows.get(i).pausedTokens();
        }
        long millis = Duration.between(started, Instant.now(clock)).toMillis();
        Timer.builder("publicapi.platform_billing.overview.duration").register(meterRegistry)
                .record(Duration.ofMillis(millis));
        LOG.atDebug().addKeyValue("tenants", rows.size()).addKeyValue("suspended", suspended)
                .addKeyValue("durationMs", millis).log("Consolidado do faturamento da plataforma calculado");
        accessLogger.log(AccessEvent.of(AccessEventType.PLATFORM_TENANT_DATA_VIEWED, actor, Map.of(
                "resource", "platform_billing_overview", "tenants", String.valueOf(rows.size()))));
        return new OverviewView(LocalDate.ofInstant(started, PlatformBillingEngine.ZONE),
                new OverviewTotals(rows.size(), withPolicy, rows.size() - withPolicy, soFar, projected, lastClosed,
                        billable, mbIn, mbOut, records, suspended, requests, nonBillable, rejected, paused),
                List.copyOf(summaries), millis);
    }

    private record Row(TenantView tenant, PositionView position, int pausedTokens) {
    }

    // ---------------------------------------------------------------- política

    @Override
    @Transactional(readOnly = true)
    public PolicyView policy(UUID tenantId) {
        access.requirePlatform(Permission.PLATFORM_BILLING_VIEW);
        access.target(tenantId);
        return inTenant(tenantId, engine::policyView);
    }

    @Override
    public PolicyView createPolicy(UUID tenantId, CreatePolicyCommand command) {
        Actor actor = access.requirePlatform(Permission.PLATFORM_BILLING_MANAGE);
        TenantView tenant = access.target(tenantId);
        PolicyView view = inTenant(tenantId, () -> engine.createPolicy(command, actor.id()));
        access.platformAudit("platform_billing.policy_created", tenant, command.reason(), Map.<String, Object>of(
                "policyId", view.policyId(), "startDate", String.valueOf(view.startDate()),
                "endDate", String.valueOf(view.endDate()), "version", 1));
        LOG.info("Política de cobrança da plataforma criada para a assessoria {}", tenantId);
        return view;
    }

    @Override
    public PolicyView addVersion(UUID tenantId, PolicyTermsCommand terms, String reason) {
        Actor actor = access.requirePlatform(Permission.PLATFORM_BILLING_MANAGE);
        TenantView tenant = access.target(tenantId);
        PolicyView view = inTenant(tenantId, () -> engine.addVersion(terms, reason, actor.id()));
        access.platformAudit("platform_billing.policy_version_added", tenant, reason, Map.<String, Object>of(
                "policyId", view.policyId(), "version", view.versions().getFirst().version()));
        return view;
    }

    @Override
    public PolicyView changeTerm(UUID tenantId, LocalDate startDate, LocalDate endDate, String reason) {
        Actor actor = access.requirePlatform(Permission.PLATFORM_BILLING_MANAGE);
        TenantView tenant = access.target(tenantId);
        PolicyView view = inTenant(tenantId, () -> engine.changeTerm(startDate, endDate, reason, actor.id()));
        access.platformAudit("platform_billing.policy_term_changed", tenant, reason, Map.<String, Object>of(
                "policyId", view.policyId(), "startDate", String.valueOf(view.startDate()),
                "endDate", String.valueOf(view.endDate())));
        return view;
    }

    // ---------------------------------------------------------------- posição e extratos

    @Override
    @Transactional(readOnly = true)
    public PositionView position(UUID tenantId) {
        Actor actor = access.requirePlatform(Permission.PLATFORM_BILLING_VIEW);
        TenantView tenant = access.target(tenantId);
        PositionView view = inTenant(tenantId, () -> engine.position(true, !tenant.active()));
        access.viewed(actor, tenantId, "platform_billing_position", null);
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public StatementView preview(UUID tenantId) {
        Actor actor = access.requirePlatform(Permission.PLATFORM_BILLING_VIEW);
        requireActive(access.target(tenantId));
        StatementView view = inTenant(tenantId, engine::preview);
        access.viewed(actor, tenantId, "platform_statement_preview", null);
        return view;
    }

    @Override
    public StatementView close(UUID tenantId, LocalDate periodStart, String reason) {
        Actor actor = access.requirePlatform(Permission.PLATFORM_BILLING_MANAGE);
        TenantView tenant = requireActive(access.target(tenantId));
        StatementView s = inTenant(tenantId, () -> engine.close(periodStart, reason, actor.id()));
        access.platformAudit(s.revision() == 1 ? "platform_statement.closed" : "platform_statement.reclosed", tenant,
                s.reason(), Map.<String, Object>of("statementId", s.id(), "period", s.periodStart() + "/" + s.periodEnd(),
                        "revision", s.revision(), "totalAmount", s.totalAmount(), "usageHash", s.usageHash()));
        LOG.atInfo().addKeyValue("tenantId", tenantId).addKeyValue("revision", s.revision())
                .log("Extrato da plataforma fechado manualmente");
        return s;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<StatementView> statements(UUID tenantId, YearMonth month, PageQuery page) {
        Actor actor = access.requirePlatform(Permission.PLATFORM_BILLING_VIEW);
        access.target(tenantId);
        PageResponse<StatementView> result = inTenant(tenantId, () -> engine.statements(month, page));
        access.viewed(actor, tenantId, "platform_statement_list", null);
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public StatementView statement(UUID tenantId, UUID statementId) {
        Actor actor = access.requirePlatform(Permission.PLATFORM_BILLING_VIEW);
        access.target(tenantId);
        StatementView s = inTenant(tenantId, () -> engine.statement(statementId));
        access.viewed(actor, tenantId, "platform_statement", statementId);
        return s;
    }

    @Override
    @Transactional(readOnly = true)
    public void exportStatement(UUID tenantId, UUID statementId, OutputStream output) {
        Actor actor = access.requirePlatform(Permission.PLATFORM_BILLING_VIEW);
        access.target(tenantId);
        StatementView s = inTenant(tenantId, () -> engine.statement(statementId));
        accessLogger.log(AccessEvent.of(AccessEventType.DATA_EXPORTED, actor, Map.of("resource", "platform_statement",
                "resourceId", statementId.toString(), "targetTenantId", tenantId.toString(),
                "usageHash", s.usageHash())));
        inTenant(tenantId, () -> engine.export(statementId, output));
    }

    // ---------------------------------------------------------------- apoio

    /** Assessoria suspensa não gera extrato (nem prévia) enquanto durar a suspensão. */
    private static TenantView requireActive(TenantView tenant) {
        if (!tenant.active()) {
            throw new BusinessRuleException("platform_statement.tenant_suspended",
                    "Assessoria suspensa: o consumo aparece com valores zerados e não há extrato enquanto durar a "
                            + "suspensão");
        }
        return tenant;
    }

    private <T> T inTenant(UUID tenantId, Supplier<T> action) {
        return access.inTenant(tenantId, PROCESS, action);
    }
}
