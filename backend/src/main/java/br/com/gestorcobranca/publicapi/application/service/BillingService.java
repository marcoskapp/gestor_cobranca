package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.PublicApiBillingUseCases;
import br.com.gestorcobranca.publicapi.application.port.out.CsvWriter;
import br.com.gestorcobranca.publicapi.application.port.out.UsageStore;
import br.com.gestorcobranca.publicapi.domain.model.IntegrationToken;
import br.com.gestorcobranca.publicapi.domain.model.PricePlan;
import br.com.gestorcobranca.publicapi.domain.model.PriceTerms;
import br.com.gestorcobranca.publicapi.domain.model.UsageStatement;
import br.com.gestorcobranca.publicapi.domain.model.UsageTotals;
import br.com.gestorcobranca.publicapi.domain.repository.IntegrationTokenRepository;
import br.com.gestorcobranca.publicapi.domain.repository.PricePlanRepository;
import br.com.gestorcobranca.publicapi.domain.repository.UsageStatementRepository;
import br.com.gestorcobranca.publicapi.domain.service.PriceCalculator;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.json.JsonCodec;
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
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Tabelas de preço e extratos mensais da API pública. O extrato é uma função pura do consumo agregado do mês e da
 * versão da tabela vigente no início do mês ({@link PriceCalculator}); o SHA-256 do consumo usado fica gravado para
 * provar que um recálculo sobre os mesmos dados produz o mesmo valor.
 */
@Service
@Transactional
class BillingService implements PublicApiBillingUseCases {

    private static final TypeReference<List<StatementLine>> LINES = new TypeReference<>() {
    };
    private static final TypeReference<List<StatementBreakdown>> BREAKDOWN = new TypeReference<>() {
    };

    private final PricePlanRepository plans;
    private final UsageStatementRepository statements;
    private final IntegrationTokenRepository tokens;
    private final UsageStore usage;
    private final CreditorUseCases creditors;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final BusinessEventLogger businessEvents;
    private final JsonCodec json;
    private final CsvWriter csv;
    private final Clock clock;

    BillingService(PricePlanRepository plans, UsageStatementRepository statements, IntegrationTokenRepository tokens,
                   UsageStore usage, CreditorUseCases creditors, AccessScope accessScope, CurrentActor currentActor,
                   AuditTrail auditTrail, AccessLogger accessLogger, BusinessEventLogger businessEvents, JsonCodec json,
                   CsvWriter csv, Clock clock) {
        this.plans = plans;
        this.statements = statements;
        this.tokens = tokens;
        this.usage = usage;
        this.creditors = creditors;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.businessEvents = businessEvents;
        this.json = json;
        this.csv = csv;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- tabelas de preço

    @Override
    @Transactional(readOnly = true)
    public List<PricePlanView> plans() {
        accessScope.requirePermission(Permission.PUBLIC_API_USAGE_VIEW);
        return plans.findAllOrderByCodeAscPlanVersionDesc().stream()
                .filter(p -> p.getCreditorId() == null || accessScope.canAccessCreditor(p.getCreditorId()))
                .map(BillingService::planView).toList();
    }

    @Override
    public PricePlanView createDraft(PricePlanCommand c) {
        requireBillingManager();
        Guard.require(c != null && c.code() != null, "price_plan.code_invalid", "Código obrigatório");
        if (c.creditorId() != null) {
            creditors.requireActive(c.creditorId());
        }
        String code = c.code().strip().toUpperCase(java.util.Locale.ROOT);
        PricePlan plan = plans.save(new PricePlan(code, plans.maxVersion(code) + 1, c.creditorId(), c.name(),
                c.effectiveFrom(), terms(c)));
        auditTrail.record(AuditEntry.of("price_plan.created", "price_plan", plan.getId())
                .creditor(plan.getCreditorId()).created("code", plan.getCode()).created("version", plan.getPlanVersion())
                .created("effectiveFrom", plan.getEffectiveFrom()));
        return planView(plan);
    }

    @Override
    public PricePlanView updateDraft(UUID planId, PricePlanCommand c) {
        requireBillingManager();
        PricePlan plan = plans.findById(planId).orElseThrow(() -> ResourceNotFoundException.of("price_plan", planId));
        Guard.require(c != null, "price_plan.required", "Dados obrigatórios");
        AuditEntry audit = AuditEntry.of("price_plan.updated", "price_plan", planId).creditor(plan.getCreditorId())
                .change("name", plan.getName(), c.name()).change("effectiveFrom", plan.getEffectiveFrom(), c.effectiveFrom())
                .change("monthlyFee", plan.getTerms().monthlyFee(), c.monthlyFee());
        plan.update(c.name(), c.effectiveFrom(), terms(c));
        plans.save(plan);
        auditTrail.record(audit);
        return planView(plan);
    }

    @Override
    public PricePlanView publish(UUID planId) {
        requireBillingManager();
        PricePlan plan = plans.findById(planId).orElseThrow(() -> ResourceNotFoundException.of("price_plan", planId));
        Instant now = Instant.now(clock);
        plan.publish(currentActor.actorId(), now, YearMonth.now(clock.withZone(TimeConfig.BUSINESS_ZONE)).atDay(1));
        plans.save(plan);
        auditTrail.record(AuditEntry.of("price_plan.published", "price_plan", planId).creditor(plan.getCreditorId())
                .change("status", "DRAFT", "PUBLISHED").meta("code", plan.getCode()).meta("version", plan.getPlanVersion()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PUBLIC_API_PRICE_PLAN_PUBLISHED)
                .creditor(plan.getCreditorId()).reference("price_plan", planId).attr("code", plan.getCode())
                .attr("version", plan.getPlanVersion()).attr("effectiveFrom", plan.getEffectiveFrom().toString())
                .amount(plan.getTerms().monthlyFee()));
        return planView(plan);
    }

    // ---------------------------------------------------------------- extratos

    @Override
    @Transactional(readOnly = true)
    public StatementView preview(UUID creditorId, YearMonth month) {
        accessScope.requirePermission(Permission.PUBLIC_API_USAGE_VIEW);
        Guard.require(creditorId != null && month != null, "usage_statement.required", "Credor e mês obrigatórios");
        accessScope.requireVisible("creditor", creditorId, creditorId);
        Computed c = compute(creditorId, month);
        return new StatementView(null, creditorId, month.atDay(1), 0, c.plan == null ? null : c.plan.getId(),
                c.plan == null ? null : c.plan.getCode(), c.plan == null ? null : c.plan.getPlanVersion(),
                c.totals.requests(), c.totals.billableRequests(), c.totals.bytesIn(), c.totals.bytesOut(),
                c.totals.records(), c.result.total(), "BRL", c.lines, c.breakdown, c.usageHash, null, null, null);
    }

    @Override
    public StatementView close(UUID creditorId, YearMonth month, String reason) {
        accessScope.requirePermission(Permission.PUBLIC_API_BILLING_MANAGE);
        Guard.require(creditorId != null && month != null, "usage_statement.required", "Credor e mês obrigatórios");
        accessScope.requireCreditor(creditorId);
        YearMonth current = YearMonth.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        Guard.require(month.isBefore(current), "usage_statement.period_open", "Só meses encerrados podem ser fechados");
        var previous = statements.findLatest(creditorId, month.atDay(1));
        if (previous.isPresent() && (reason == null || reason.isBlank())) {
            throw new ConflictException("usage_statement.already_closed",
                    "Extrato do mês já fechado; informe o motivo para gerar nova revisão");
        }
        Computed c = compute(creditorId, month);
        int revision = previous.map(s -> s.getRevision() + 1).orElse(1);
        UsageStatement statement = statements.save(new UsageStatement(creditorId, month.atDay(1), revision, c.plan,
                c.totals, c.result.total(), json.toJson(c.lines), json.toJson(c.breakdown), c.usageHash,
                Instant.now(clock), currentActor.actorId(), reason));
        auditTrail.record(AuditEntry.of(revision == 1 ? "usage_statement.closed" : "usage_statement.reclosed",
                        "usage_statement", statement.getId()).creditor(creditorId).reason(statement.getReason())
                .created("period", month.toString()).created("revision", revision)
                .created("totalAmount", statement.getTotalAmount()).created("usageHash", c.usageHash)
                .meta("previousStatementId", previous.map(UsageStatement::getId).orElse(null)));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PUBLIC_API_STATEMENT_CLOSED).creditor(creditorId)
                .reference("usage_statement", statement.getId()).amount(statement.getTotalAmount())
                .attr("period", month.toString()).attr("revision", revision).attr("requests", c.totals.requests())
                .attr("billableRequests", c.totals.billableRequests()).attr("bytesIn", c.totals.bytesIn())
                .attr("bytesOut", c.totals.bytesOut()).attr("records", c.totals.records())
                .attr("pricePlanCode", statement.getPricePlanCode()).attr("pricePlanVersion", statement.getPricePlanVersion()));
        return view(statement);
    }

    @Override
    @Transactional(readOnly = true)
    public StatementView statement(UUID statementId) {
        accessScope.requirePermission(Permission.PUBLIC_API_USAGE_VIEW);
        return view(loadVisible(statementId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<StatementView> statements(UUID creditorId, YearMonth month, PageQuery page) {
        accessScope.requirePermission(Permission.PUBLIC_API_USAGE_VIEW);
        if (creditorId != null) {
            accessScope.requireVisible("creditor", creditorId, creditorId);
        }
        Set<UUID> allowed = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(null);
        return PageResponse.of(statements.search(allowed, creditorId, month == null ? null : month.atDay(1),
                page.toPageable(Sort.by(Sort.Direction.DESC, "periodStart").and(Sort.by(Sort.Direction.DESC, "revision")))),
                this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public void exportStatement(UUID statementId, OutputStream output) {
        accessScope.requirePermission(Permission.PUBLIC_API_USAGE_VIEW);
        StatementView s = view(loadVisible(statementId));
        accessLogger.log(AccessEvent.of(AccessEventType.DATA_EXPORTED, currentActor.get(), Map.of(
                "resource", "usage_statement", "resourceId", statementId.toString(), "usageHash", s.usageHash())));
        try (CsvWriter.Sheet sheet = csv.open(output, List.of("secao", "item", "descricao", "quantidade", "franquia",
                "cobravel", "preco_unitario", "valor"))) {
            sheet.row(List.of("extrato", "credor", s.creditorId(), "", "", "", "", ""));
            sheet.row(List.of("extrato", "periodo", s.periodStart(), "", "", "", "", ""));
            sheet.row(List.of("extrato", "revisao", s.revision(), "", "", "", "", ""));
            sheet.row(List.of("extrato", "tabela", s.pricePlanCode() == null ? "sem tabela" : s.pricePlanCode() + " v"
                    + s.pricePlanVersion(), "", "", "", "", ""));
            sheet.row(List.of("extrato", "hash_consumo", s.usageHash(), "", "", "", "", ""));
            for (StatementLine l : s.lines()) {
                sheet.row(List.of("linha", l.metric(), "", l.quantity(), l.included(), l.billable(), l.unitPrice(),
                        l.amount()));
            }
            sheet.row(List.of("total", "", "", "", "", "", "", s.totalAmount()));
            for (StatementBreakdown b : s.breakdown()) {
                sheet.row(List.of("detalhe_" + b.kind().toLowerCase(java.util.Locale.ROOT), b.key(), b.label(),
                        b.totals().getOrDefault("requests", 0L), "", b.totals().getOrDefault("billableRequests", 0L),
                        b.totals().getOrDefault("bytesIn", 0L) + "/" + b.totals().getOrDefault("bytesOut", 0L),
                        b.totals().getOrDefault("records", 0L)));
            }
        }
    }

    // ---------------------------------------------------------------- cálculo

    private record Computed(PricePlan plan, UsageTotals totals, PriceCalculator.Result result, List<StatementLine> lines,
                            List<StatementBreakdown> breakdown, String usageHash) {
    }

    private Computed compute(UUID creditorId, YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        UsageStore.Filter filter = new UsageStore.Filter(from, to, null, creditorId, null);
        UsageTotals totals = usage.totals(filter);
        PricePlan plan = plans.findApplicable(creditorId, month.atDay(1)).orElse(null);
        PriceCalculator.Result result = plan == null
                ? new PriceCalculator.Result(List.of(), BigDecimal.ZERO.setScale(2))
                : PriceCalculator.calculate(plan.getTerms(), totals);
        List<StatementLine> lines = result.lines().stream().map(l -> new StatementLine(l.metric(), l.quantity(),
                l.included(), l.billable(), l.unitPrice(), l.amount())).toList();
        List<UsageStore.TokenRow> byToken = usage.byToken(filter);
        Map<UUID, IntegrationToken> names = tokens.findAllByIdIn(byToken.stream().map(UsageStore.TokenRow::tokenId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(IntegrationToken::getId, Function.identity()));
        List<StatementBreakdown> breakdown = new ArrayList<>();
        byToken.stream().sorted(java.util.Comparator.comparing(r -> r.tokenId().toString())).forEach(r -> {
            IntegrationToken t = names.get(r.tokenId());
            breakdown.add(new StatementBreakdown("TOKEN", r.tokenId().toString(),
                    t == null ? null : t.getDisplayPrefix() + " · " + t.getName(), totalsMap(r.totals())));
        });
        usage.byRoute(filter).stream().sorted(java.util.Comparator.comparing(r -> r.method() + " " + r.route()))
                .forEach(r -> breakdown.add(new StatementBreakdown("ROUTE", r.method() + " " + r.route(), null,
                        totalsMap(r.totals()))));
        String hash = sha256(json.toJson(Map.of("creditorId", creditorId.toString(), "period", month.toString(),
                "totals", totalsMap(totals), "breakdown", breakdown)));
        return new Computed(plan, totals, result, lines, breakdown, hash);
    }

    private static Map<String, Long> totalsMap(UsageTotals t) {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("requests", t.requests());
        m.put("billableRequests", t.billableRequests());
        m.put("throttled", t.throttled());
        m.put("clientErrors", t.clientErrors());
        m.put("serverErrors", t.serverErrors());
        m.put("bytesIn", t.bytesIn());
        m.put("bytesOut", t.bytesOut());
        m.put("records", t.records());
        return m;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- apoio

    private void requireBillingManager() {
        accessScope.requirePermission(Permission.PUBLIC_API_BILLING_MANAGE);
        accessScope.requireAllCreditors("price_plan");
    }

    private UsageStatement loadVisible(UUID id) {
        UsageStatement s = statements.findById(id).orElseThrow(() -> ResourceNotFoundException.of("usage_statement", id));
        accessScope.requireVisible("usage_statement", id, s.getCreditorId());
        return s;
    }

    private static PriceTerms terms(PricePlanCommand c) {
        return new PriceTerms(c.monthlyFee(), c.includedRequests(), c.pricePerRequest(), c.includedMbIn(),
                c.pricePerMbIn(), c.includedMbOut(), c.pricePerMbOut(), c.includedRecords(), c.pricePerRecord());
    }

    static PricePlanView planView(PricePlan p) {
        PriceTerms t = p.getTerms();
        return new PricePlanView(p.getId(), p.getCode(), p.getPlanVersion(), p.getCreditorId(), p.getName(),
                p.getCurrency(), p.getEffectiveFrom(), p.getStatus().name(), t.monthlyFee(), t.includedRequests(),
                t.pricePerRequest(), t.includedMbIn(), t.pricePerMbIn(), t.includedMbOut(), t.pricePerMbOut(),
                t.includedRecords(), t.pricePerRecord(), p.getPublishedAt(), p.getPublishedBy());
    }

    private StatementView view(UsageStatement s) {
        return new StatementView(s.getId(), s.getCreditorId(), s.getPeriodStart(), s.getRevision(), s.getPricePlanId(),
                s.getPricePlanCode(), s.getPricePlanVersion(), s.getRequests(), s.getBillableRequests(), s.getBytesIn(),
                s.getBytesOut(), s.getRecords(), s.getTotalAmount(), s.getCurrency(),
                json.fromJson(s.getLines(), LINES), json.fromJson(s.getBreakdown(), BREAKDOWN), s.getUsageHash(),
                s.getClosedAt(), s.getClosedBy(), s.getReason());
    }
}
