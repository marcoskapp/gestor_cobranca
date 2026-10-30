package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.organization.application.port.in.ContractIntegrationPricingUseCases;
import br.com.gestorcobranca.organization.application.port.in.ContractIntegrationPricingUseCases.IntegrationPricingView;
import br.com.gestorcobranca.publicapi.application.port.in.PublicApiBillingUseCases;
import br.com.gestorcobranca.publicapi.application.port.out.CsvWriter;
import br.com.gestorcobranca.publicapi.application.port.out.UsageStore;
import br.com.gestorcobranca.publicapi.domain.model.BillableUsage;
import br.com.gestorcobranca.publicapi.domain.model.IntegrationToken;
import br.com.gestorcobranca.publicapi.domain.model.PriceTerms;
import br.com.gestorcobranca.publicapi.domain.model.UsageStatement;
import br.com.gestorcobranca.publicapi.domain.model.UsageTotals;
import br.com.gestorcobranca.publicapi.domain.repository.IntegrationTokenRepository;
import br.com.gestorcobranca.publicapi.domain.repository.UsageStatementRepository;
import br.com.gestorcobranca.publicapi.domain.service.BillingCycles;
import br.com.gestorcobranca.publicapi.domain.service.BillingCycles.Cycle;
import br.com.gestorcobranca.publicapi.domain.service.PriceCalculator;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
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
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Extratos por ciclo de consumo da API pública. O extrato é uma função pura do consumo agregado do ciclo e da versão
 * das condições de cobrança do contrato do credor registrada antes do início do ciclo ({@link PriceCalculator}); o
 * SHA-256 do consumo usado fica gravado para provar que um recálculo sobre os mesmos dados produz o mesmo valor.
 * <p>
 * <b>Sequência dos ciclos:</b> o próximo ciclo começa no dia seguinte ao fim do último ciclo fechado e fecha no
 * primeiro dia de fechamento (das condições que valem no seu início) igual ou posterior. Se esse ciclo terminou sem
 * consumo, não gera extrato: o próximo extrato é o do ciclo (no calendário do dia de fechamento) que contém o primeiro
 * consumo posterior. Sem ciclo anterior, idem a partir do primeiro consumo do credor. Sem condições no contrato, o
 * ciclo fecha no último dia do mês e o extrato registra o consumo com valor zero.
 */
@Service
@Transactional
class BillingService implements PublicApiBillingUseCases {

    private static final ZoneId ZONE = TimeConfig.BUSINESS_ZONE;
    private static final TypeReference<List<StatementLine>> LINES = new TypeReference<>() {
    };
    private static final TypeReference<List<StatementBreakdown>> BREAKDOWN = new TypeReference<>() {
    };

    private final UsageStatementRepository statements;
    private final IntegrationTokenRepository tokens;
    private final UsageStore usage;
    private final ContractIntegrationPricingUseCases contractPricing;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final BusinessEventLogger businessEvents;
    private final JsonCodec json;
    private final CsvWriter csv;
    private final Clock clock;

    BillingService(UsageStatementRepository statements, IntegrationTokenRepository tokens, UsageStore usage,
                   ContractIntegrationPricingUseCases contractPricing, AccessScope accessScope,
                   CurrentActor currentActor, AuditTrail auditTrail, AccessLogger accessLogger,
                   BusinessEventLogger businessEvents, JsonCodec json, CsvWriter csv, Clock clock) {
        this.statements = statements;
        this.tokens = tokens;
        this.usage = usage;
        this.contractPricing = contractPricing;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.businessEvents = businessEvents;
        this.json = json;
        this.csv = csv;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- extratos

    @Override
    @Transactional(readOnly = true)
    public StatementView preview(UUID creditorId) {
        accessScope.requirePermission(Permission.PUBLIC_API_USAGE_VIEW);
        Guard.require(creditorId != null, "usage_statement.required", "Credor obrigatório");
        accessScope.requireVisible("creditor", creditorId, creditorId);
        Instant now = Instant.now(clock);
        Pending pending = pendingCycle(creditorId, now).orElseGet(() -> {
            // nada a fechar: ciclo em curso a partir de hoje (ou do dia seguinte ao último ciclo fechado)
            LocalDate today = LocalDate.ofInstant(now, ZONE);
            LocalDate start = statements.findLastCycle(creditorId).map(s -> s.getPeriodEnd().plusDays(1))
                    .filter(d -> d.isAfter(today)).orElse(today);
            return cycleStartingAt(creditorId, start);
        });
        Computed c = compute(creditorId, pending);
        return new StatementView(null, creditorId, pending.cycle().start(), pending.cycle().end(),
                pending.cycle().endedAt(now, ZONE), 0, pending.cycle().closingDay(),
                pending.pricing().map(IntegrationPricingView::contractId).orElse(null),
                pending.pricing().map(IntegrationPricingView::contractNumber).orElse(null),
                pending.pricing().map(IntegrationPricingView::id).orElse(null),
                pending.pricing().map(IntegrationPricingView::version).orElse(null), c.totals.requests(),
                c.billable.requests(), c.billable.mbIn(), c.billable.mbOut(), c.billable.records(), c.result.total(),
                "BRL", c.lines, c.breakdown, c.usageHash, null, null, null);
    }

    @Override
    public StatementView close(UUID creditorId, LocalDate periodStart, String reason) {
        accessScope.requirePermission(Permission.PUBLIC_API_BILLING_MANAGE);
        Guard.require(creditorId != null, "usage_statement.required", "Credor obrigatório");
        accessScope.requireCreditor(creditorId);
        Instant now = Instant.now(clock);
        if (periodStart != null) {
            Optional<UsageStatement> existing = statements.findLatest(creditorId, periodStart);
            if (existing.isPresent()) {
                if (reason == null || reason.isBlank()) {
                    throw new ConflictException("usage_statement.already_closed",
                            "Extrato do ciclo já fechado; informe o motivo para gerar nova revisão");
                }
                UsageStatement previous = existing.get();
                // mesma vigência e mesmas condições (registradas antes do início do ciclo): só o consumo pode mudar
                Cycle cycle = new Cycle(previous.getPeriodStart(), previous.getPeriodEnd(), previous.getUsageClosingDay());
                return save(creditorId, new Pending(cycle, applicablePricing(creditorId, cycle.start())),
                        previous, reason);
            }
        }
        Pending pending = pendingCycle(creditorId, now).orElseThrow(() -> new BusinessRuleException(
                "usage_statement.nothing_to_close", "Não há ciclo com consumo a fechar para o credor"));
        if (periodStart != null && !periodStart.equals(pending.cycle().start())) {
            throw new BusinessRuleException("usage_statement.period_invalid",
                    "O próximo ciclo a fechar começa em " + pending.cycle().start());
        }
        Guard.require(pending.cycle().endedAt(now, ZONE), "usage_statement.period_open",
                "Só ciclos encerrados podem ser fechados");
        return save(creditorId, pending, null, reason);
    }

    @Override
    public int closeEndedCycles(UUID creditorId) {
        accessScope.requirePermission(Permission.PUBLIC_API_BILLING_MANAGE);
        accessScope.requireCreditor(creditorId);
        Instant now = Instant.now(clock);
        int closed = 0;
        Optional<Pending> pending = pendingCycle(creditorId, now);
        while (pending.isPresent() && pending.get().cycle().endedAt(now, ZONE)) {
            save(creditorId, pending.get(), null, null);
            closed++;
            pending = pendingCycle(creditorId, now);
        }
        return closed;
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
                month == null ? null : month.atEndOfMonth(),
                page.toPageable(Sort.by(Sort.Direction.DESC, "periodEnd").and(Sort.by(Sort.Direction.DESC, "revision")))),
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
            sheet.row(List.of("extrato", "ciclo", s.periodStart() + " a " + s.periodEnd(), "", "", "", "", ""));
            sheet.row(List.of("extrato", "dia_fechamento", s.usageClosingDay(), "", "", "", "", ""));
            sheet.row(List.of("extrato", "revisao", s.revision(), "", "", "", "", ""));
            sheet.row(List.of("extrato", "condicoes", s.pricingVersion() == null ? "contrato sem condições de cobrança"
                    : "contrato " + s.contractNumber() + " v" + s.pricingVersion(), "", "", "", "", ""));
            sheet.row(List.of("extrato", "unidade_trafego", "MB = 1.048.576 bytes, arredondado para cima por token",
                    "", "", "", "", ""));
            sheet.row(List.of("extrato", "hash_consumo", s.usageHash(), "", "", "", "", ""));
            for (StatementLine l : s.lines()) {
                sheet.row(List.of("linha", l.metric(), "", l.quantity(), l.included(), l.billable(), l.unitPrice(),
                        l.amount()));
            }
            sheet.row(List.of("total", "", "", "", "", "", "", s.totalAmount()));
            for (StatementBreakdown b : s.breakdown()) {
                Map<String, Long> t = b.totals();
                sheet.row(List.of("detalhe_" + b.kind().toLowerCase(Locale.ROOT), b.key(), b.label() == null ? "" : b.label(),
                        t.getOrDefault("requests", 0L), "", t.getOrDefault("billableRequests", 0L),
                        "TOKEN".equals(b.kind()) ? t.getOrDefault("billableMbIn", 0L) + " MB / "
                                + t.getOrDefault("billableMbOut", 0L) + " MB" : "",
                        t.getOrDefault("records", 0L)));
            }
        }
    }

    // ---------------------------------------------------------------- ciclos

    /** Ciclo e condições do contrato aplicadas a ele. */
    private record Pending(Cycle cycle, Optional<IntegrationPricingView> pricing) {
    }

    /**
     * Próximo ciclo a fechar do credor: o contíguo ao último fechado se tiver consumo ou ainda estiver em curso; senão o
     * que começa no dia do primeiro consumo posterior. Vazio quando não há consumo a fechar.
     */
    private Optional<Pending> pendingCycle(UUID creditorId, Instant now) {
        Optional<UsageStatement> last = statements.findLastCycle(creditorId);
        LocalDate earliestStart = null;
        Instant searchFrom = Instant.EPOCH;
        if (last.isPresent()) {
            Pending contiguous = cycleStartingAt(creditorId, last.get().getPeriodEnd().plusDays(1));
            Instant from = contiguous.cycle().from(ZONE);
            Instant to = contiguous.cycle().to(ZONE);
            Optional<Instant> first = usage.firstUsageAt(creditorId, from);
            if (first.filter(t -> t.isBefore(to)).isPresent() || !contiguous.cycle().endedAt(now, ZONE)) {
                return Optional.of(contiguous);
            }
            earliestStart = contiguous.cycle().end().plusDays(1);
            searchFrom = to;
        }
        LocalDate lowerBound = earliestStart;
        return usage.firstUsageAt(creditorId, searchFrom)
                .map(first -> cycleStartingAt(creditorId, nominalStart(creditorId, LocalDate.ofInstant(first, ZONE),
                        lowerBound)));
    }

    /**
     * Início do ciclo que contém o dia do primeiro consumo, no calendário do dia de fechamento que vale nesse dia —
     * nunca antes do dia seguinte ao último ciclo (sem sobreposição).
     */
    private LocalDate nominalStart(UUID creditorId, LocalDate firstUsageDay, LocalDate lowerBound) {
        int closingDay = applicablePricing(creditorId, firstUsageDay).map(IntegrationPricingView::usageClosingDay)
                .orElse(ContractIntegrationPricingUseCases.DEFAULT_USAGE_CLOSING_DAY);
        LocalDate start = BillingCycles.containing(firstUsageDay, closingDay).start();
        return lowerBound != null && start.isBefore(lowerBound) ? lowerBound : start;
    }

    private Pending cycleStartingAt(UUID creditorId, LocalDate start) {
        Optional<IntegrationPricingView> pricing = applicablePricing(creditorId, start);
        int closingDay = pricing.map(IntegrationPricingView::usageClosingDay)
                .orElse(ContractIntegrationPricingUseCases.DEFAULT_USAGE_CLOSING_DAY);
        return new Pending(BillingCycles.startingAt(start, closingDay), pricing);
    }

    /** Condições registradas antes das 00:00 do primeiro dia do ciclo (não retroatividade). */
    private Optional<IntegrationPricingView> applicablePricing(UUID creditorId, LocalDate cycleStart) {
        return contractPricing.applicable(creditorId, cycleStart.atStartOfDay(ZONE).toInstant());
    }

    private StatementView save(UUID creditorId, Pending pending, UsageStatement previous, String reason) {
        Computed c = compute(creditorId, pending);
        Cycle cycle = pending.cycle();
        int revision = previous == null ? 1 : previous.getRevision() + 1;
        UsageStatement.AppliedPricing applied = pending.pricing().map(p -> new UsageStatement.AppliedPricing(
                p.contractId(), p.contractNumber(), p.id(), p.version())).orElse(null);
        UsageStatement statement = statements.save(new UsageStatement(creditorId, cycle.start(), cycle.end(), revision,
                cycle.closingDay(), applied, c.totals.requests(), c.billable, c.result.total(), json.toJson(c.lines),
                json.toJson(c.breakdown), c.usageHash, Instant.now(clock), currentActor.actorId(), reason));
        String period = cycle.start() + "/" + cycle.end();
        auditTrail.record(AuditEntry.of(revision == 1 ? "usage_statement.closed" : "usage_statement.reclosed",
                        "usage_statement", statement.getId()).creditor(creditorId).reason(statement.getReason())
                .created("period", period).created("revision", revision)
                .created("totalAmount", statement.getTotalAmount()).created("usageHash", c.usageHash)
                .meta("pricingVersion", statement.getPricingVersion())
                .meta("previousStatementId", previous == null ? null : previous.getId()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PUBLIC_API_STATEMENT_CLOSED).creditor(creditorId)
                .reference("usage_statement", statement.getId()).amount(statement.getTotalAmount())
                .attr("periodStart", cycle.start().toString()).attr("periodEnd", cycle.end().toString())
                .attr("closingDay", cycle.closingDay()).attr("revision", revision)
                .attr("requests", c.totals.requests()).attr("billableRequests", c.billable.requests())
                .attr("clientErrors", c.totals.clientErrors()).attr("throttled", c.totals.throttled())
                .attr("serverErrors", c.totals.serverErrors()).attr("billableMbIn", c.billable.mbIn())
                .attr("billableMbOut", c.billable.mbOut()).attr("records", c.billable.records())
                .attr("contractNumber", statement.getContractNumber())
                .attr("pricingVersion", statement.getPricingVersion()));
        return view(statement);
    }

    // ---------------------------------------------------------------- cálculo

    private record Computed(UsageTotals totals, BillableUsage billable, PriceCalculator.Result result,
                            List<StatementLine> lines, List<StatementBreakdown> breakdown, String usageHash) {
    }

    private Computed compute(UUID creditorId, Pending pending) {
        Cycle cycle = pending.cycle();
        UsageStore.Filter filter = new UsageStore.Filter(cycle.from(ZONE), cycle.to(ZONE), null, creditorId, null);
        UsageTotals totals = usage.totals(filter);
        List<UsageStore.TokenRow> byToken = usage.byToken(filter);
        BillableUsage billable = BillableUsage.sumOfTokens(byToken.stream().map(UsageStore.TokenRow::totals).toList());
        PriceCalculator.Result result = pending.pricing()
                .map(p -> PriceCalculator.calculate(terms(p), billable))
                .orElseGet(() -> new PriceCalculator.Result(List.of(), BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY)));
        List<StatementLine> lines = result.lines().stream().map(l -> new StatementLine(l.metric(), l.quantity(),
                l.included(), l.billable(), l.unitPrice(), l.amount())).toList();
        Map<UUID, IntegrationToken> names = tokens.findAllByIdIn(byToken.stream().map(UsageStore.TokenRow::tokenId)
                .collect(Collectors.toSet())).stream().collect(Collectors.toMap(IntegrationToken::getId, Function.identity()));
        List<StatementBreakdown> breakdown = new ArrayList<>();
        byToken.stream().sorted(Comparator.comparing(r -> r.tokenId().toString())).forEach(r -> {
            IntegrationToken t = names.get(r.tokenId());
            BillableUsage b = BillableUsage.ofToken(r.totals());
            Map<String, Long> m = new LinkedHashMap<>();
            m.put("requests", r.totals().requests());
            m.put("billableRequests", b.requests());
            m.put("billableMbIn", b.mbIn());
            m.put("billableMbOut", b.mbOut());
            m.put("records", b.records());
            breakdown.add(new StatementBreakdown("TOKEN", r.tokenId().toString(),
                    t == null ? null : t.getDisplayPrefix() + " · " + t.getName(), m));
        });
        usage.byRoute(filter).stream().sorted(Comparator.comparing(r -> r.method() + " " + r.route())).forEach(r -> {
            Map<String, Long> m = new LinkedHashMap<>();
            m.put("requests", r.totals().requests());
            m.put("billableRequests", r.totals().billableRequests());
            m.put("records", r.totals().records());
            breakdown.add(new StatementBreakdown("ROUTE", r.method() + " " + r.route(), null, m));
        });
        Map<String, Object> hashed = new LinkedHashMap<>();
        hashed.put("creditorId", creditorId.toString());
        hashed.put("periodStart", cycle.start().toString());
        hashed.put("periodEnd", cycle.end().toString());
        hashed.put("totals", totalsMap(totals, billable));
        hashed.put("breakdown", breakdown);
        return new Computed(totals, billable, result, lines, breakdown, sha256(json.toJson(hashed)));
    }

    private static PriceTerms terms(IntegrationPricingView p) {
        return new PriceTerms(p.monthlyFee(), p.includedRequests(), p.pricePerRequest(), p.includedMbIn(),
                p.pricePerMbIn(), p.includedMbOut(), p.pricePerMbOut(), p.includedRecords(), p.pricePerRecord());
    }

    private static Map<String, Long> totalsMap(UsageTotals t, BillableUsage b) {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("requests", t.requests());
        m.put("billableRequests", b.requests());
        m.put("throttled", t.throttled());
        m.put("clientErrors", t.clientErrors());
        m.put("serverErrors", t.serverErrors());
        m.put("billableMbIn", b.mbIn());
        m.put("billableMbOut", b.mbOut());
        m.put("records", b.records());
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

    private UsageStatement loadVisible(UUID id) {
        UsageStatement s = statements.findById(id).orElseThrow(() -> ResourceNotFoundException.of("usage_statement", id));
        accessScope.requireVisible("usage_statement", id, s.getCreditorId());
        return s;
    }

    private StatementView view(UsageStatement s) {
        Instant now = Instant.now(clock);
        Cycle cycle = new Cycle(s.getPeriodStart(), s.getPeriodEnd(), s.getUsageClosingDay());
        return new StatementView(s.getId(), s.getCreditorId(), s.getPeriodStart(), s.getPeriodEnd(),
                cycle.endedAt(now, ZONE), s.getRevision(), s.getUsageClosingDay(), s.getContractId(),
                s.getContractNumber(), s.getPricingId(), s.getPricingVersion(), s.getRequests(), s.getBillableRequests(),
                s.getBillableMbIn(), s.getBillableMbOut(), s.getRecords(), s.getTotalAmount(), s.getCurrency(),
                json.fromJson(s.getLines(), LINES), json.fromJson(s.getBreakdown(), BREAKDOWN), s.getUsageHash(),
                s.getClosedAt(), s.getClosedBy(), s.getReason());
    }
}
