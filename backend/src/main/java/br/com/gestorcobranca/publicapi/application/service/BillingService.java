package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.organization.application.port.in.ContractIntegrationPricingUseCases;
import br.com.gestorcobranca.organization.application.port.in.ContractIntegrationPricingUseCases.IntegrationPricingView;
import br.com.gestorcobranca.publicapi.application.PublicApiProperties;
import br.com.gestorcobranca.publicapi.application.port.in.PublicApiBillingUseCases;
import br.com.gestorcobranca.publicapi.application.port.out.CsvWriter;
import br.com.gestorcobranca.publicapi.application.port.out.UsageStore;
import br.com.gestorcobranca.publicapi.domain.model.BillableUsage;
import br.com.gestorcobranca.publicapi.domain.model.IntegrationToken;
import br.com.gestorcobranca.publicapi.domain.model.PriceTerms;
import br.com.gestorcobranca.publicapi.domain.model.Proration;
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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * consumo e sem mensalidade não gera extrato (decisão 624). Sem ciclo anterior, o primeiro ciclo é o que contém o
 * primeiro consumo do credor. Sem condições no contrato, o ciclo fecha no último dia do mês e o extrato registra o
 * consumo com valor zero. No ciclo de transição (mudança do dia de fechamento) mensalidade e franquias são
 * proporcionais aos dias ({@link BillingCycles#proration}).
 */
@Service
@Transactional
class BillingService implements PublicApiBillingUseCases {

    private static final ZoneId ZONE = TimeConfig.BUSINESS_ZONE;
    private static final Logger LOG = LoggerFactory.getLogger(BillingService.class);
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
    private final PublicApiProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    BillingService(UsageStatementRepository statements, IntegrationTokenRepository tokens, UsageStore usage,
                   ContractIntegrationPricingUseCases contractPricing, AccessScope accessScope,
                   CurrentActor currentActor, AuditTrail auditTrail, AccessLogger accessLogger,
                   BusinessEventLogger businessEvents, JsonCodec json, CsvWriter csv, PublicApiProperties properties,
                   MeterRegistry meterRegistry, Clock clock) {
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
        this.properties = properties;
        this.meterRegistry = meterRegistry;
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
                pending.cycle().endedAt(now, ZONE), 0, pending.cycle().closingDay(), c.proration.cycleDays(),
                c.proration.referenceDays(), pending.pricing().map(IntegrationPricingView::contractId).orElse(null),
                pending.pricing().map(IntegrationPricingView::contractNumber).orElse(null),
                pending.pricing().map(IntegrationPricingView::id).orElse(null),
                pending.pricing().map(IntegrationPricingView::version).orElse(null), c.totals.requests(),
                c.billable.requests(), c.billable.mbIn(), c.billable.mbOut(), c.billable.records(), c.result.total(),
                "BRL", c.lines, c.breakdown, c.usageHash, null, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public BillingPositionView position(UUID creditorId) {
        accessScope.requirePermission(Permission.PUBLIC_API_USAGE_VIEW);
        Guard.require(creditorId != null, "usage_statement.required", "Credor obrigatório");
        accessScope.requireVisible("creditor", creditorId, creditorId);
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.ofInstant(now, ZONE);
        Pending current = currentCycle(creditorId, now, today);
        Cycle cycle = current.cycle();
        Proration proration = BillingCycles.proration(cycle);
        Computed c = compute(creditorId, current);
        Optional<PriceTerms> terms = current.pricing().map(BillingService::terms);

        int daysElapsed = (int) java.time.temporal.ChronoUnit.DAYS.between(cycle.start(), today) + 1;
        int daysRemaining = (int) java.time.temporal.ChronoUnit.DAYS.between(today, cycle.end());
        List<AllowanceUse> allowances = c.lines.stream().filter(l -> !"MONTHLY_FEE".equals(l.metric()))
                .map(l -> new AllowanceUse(l.metric(), l.quantity(), l.included(), l.included() == 0 ? null
                        : BigDecimal.valueOf(l.quantity()).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(l.included()), 2, RoundingMode.HALF_EVEN)))
                .toList();
        List<GrowthPoint> daily = growth(creditorId, cycle, today, terms, proration);

        Cycle previousCycle = statements.findLastCycle(creditorId)
                .filter(s -> s.getPeriodEnd().equals(cycle.start().minusDays(1)))
                .map(s -> new Cycle(s.getPeriodStart(), s.getPeriodEnd(), s.getUsageClosingDay()))
                .orElseGet(() -> {
                    LocalDate prevEnd = cycle.start().minusDays(1);
                    int day = applicablePricing(creditorId, prevEnd).map(IntegrationPricingView::usageClosingDay)
                            .orElse(ContractIntegrationPricingUseCases.DEFAULT_USAGE_CLOSING_DAY);
                    return BillingCycles.containing(prevEnd, day);
                });
        Optional<UsageStatement> previousStatement = statements.findLatest(creditorId, previousCycle.start());
        Optional<PriceTerms> previousTerms = applicablePricing(creditorId, previousCycle.start()).map(BillingService::terms);
        List<GrowthPoint> previousDaily = growth(creditorId, previousCycle, previousCycle.end(), previousTerms,
                BillingCycles.proration(previousCycle));
        PreviousCycle previous = new PreviousCycle(previousCycle.start(), previousCycle.end(),
                previousStatement.map(UsageStatement::getId).orElse(null),
                previousStatement.map(UsageStatement::getTotalAmount)
                        .orElseGet(() -> previousDaily.isEmpty() ? BigDecimal.ZERO.setScale(2)
                                : previousDaily.getLast().amount()));

        Projection projection = project(c.billable, daysElapsed, daysRemaining, terms, proration);
        Optional<Instant> updatedAt = usage.lastUpdatedAt(new UsageStore.Filter(cycle.from(ZONE), cycle.to(ZONE), null,
                creditorId, null));
        long millis = java.time.Duration.between(now, Instant.now(clock)).toMillis();
        Timer.builder("publicapi.billing.position.duration").register(meterRegistry)
                .record(java.time.Duration.ofMillis(millis));
        LOG.atDebug().addKeyValue("creditorId", creditorId).addKeyValue("durationMs", millis)
                .log("Posição do ciclo de consumo calculada");
        return new BillingPositionView(creditorId, cycle.start(), cycle.end(), cycle.closingDay(),
                proration.cycleDays(), proration.referenceDays(), today, daysElapsed, daysRemaining,
                current.pricing().map(IntegrationPricingView::contractNumber).orElse(null),
                current.pricing().map(IntegrationPricingView::version).orElse(null), c.totals.requests(),
                c.billable.requests(), c.billable.mbIn(), c.billable.mbOut(), c.billable.records(), c.result.total(),
                "BRL", c.lines, allowances, c.breakdown, daily, previous, previousDaily, projection,
                updatedAt.orElse(null), properties.usageFlushInterval().toSeconds(), millis);
    }

    /** Ciclo que contém hoje: avança pelos ciclos contíguos a partir do próximo a fechar. */
    private Pending currentCycle(UUID creditorId, Instant now, LocalDate today) {
        Pending p = pendingCycle(creditorId, now).orElseGet(() -> cycleStartingAt(creditorId,
                statements.findLastCycle(creditorId).map(s -> s.getPeriodEnd().plusDays(1))
                        .filter(d -> !d.isAfter(today)).orElseGet(() -> nominalStart(creditorId, today))));
        while (p.cycle().end().isBefore(today)) {
            p = cycleStartingAt(creditorId, p.cycle().end().plusDays(1));
        }
        return p;
    }

    /** Acumulado diário do ciclo até {@code until}: MB arredondados por token sobre o acumulado, como no fechamento. */
    private List<GrowthPoint> growth(UUID creditorId, Cycle cycle, LocalDate until, Optional<PriceTerms> terms,
                                     Proration proration) {
        LocalDate last = until.isAfter(cycle.end()) ? cycle.end() : until;
        if (last.isBefore(cycle.start())) {
            return List.of();
        }
        Map<LocalDate, List<UsageStore.DailyTokenRow>> byDay = usage.dailyByToken(new UsageStore.Filter(cycle.from(ZONE),
                cycle.to(ZONE), null, creditorId, null), ZONE.getId()).stream()
                .collect(Collectors.groupingBy(UsageStore.DailyTokenRow::day));
        Map<UUID, UsageTotals> cumulative = new LinkedHashMap<>();
        List<GrowthPoint> points = new ArrayList<>();
        int day = 1;
        for (LocalDate d = cycle.start(); !d.isAfter(last); d = d.plusDays(1), day++) {
            for (UsageStore.DailyTokenRow r : byDay.getOrDefault(d, List.of())) {
                cumulative.merge(r.tokenId(), r.totals(), UsageTotals::plus);
            }
            BillableUsage b = BillableUsage.sumOfTokens(cumulative.values());
            BigDecimal amount = terms.map(t -> PriceCalculator.calculate(t, b, proration).total())
                    .orElse(BigDecimal.ZERO.setScale(2));
            points.add(new GrowthPoint(day, d, b.requests(), b.mbIn(), b.mbOut(), b.records(), amount));
        }
        return points;
    }

    private static Projection project(BillableUsage current, int daysElapsed, int daysRemaining,
                                      Optional<PriceTerms> terms, Proration proration) {
        BillableUsage projected = new BillableUsage(projectOne(current.requests(), daysElapsed, daysRemaining),
                projectOne(current.mbIn(), daysElapsed, daysRemaining),
                projectOne(current.mbOut(), daysElapsed, daysRemaining),
                projectOne(current.records(), daysElapsed, daysRemaining));
        BigDecimal amount = terms.map(t -> PriceCalculator.calculate(t, projected, proration).total())
                .orElse(BigDecimal.ZERO.setScale(2));
        return new Projection("DAILY_AVERAGE", projected.requests(), projected.mbIn(), projected.mbOut(),
                projected.records(), amount);
    }

    static long projectOne(long current, int daysElapsed, int daysRemaining) {
        if (current == 0 || daysRemaining <= 0 || daysElapsed <= 0) {
            return current;
        }
        return current + BigDecimal.valueOf(current).multiply(BigDecimal.valueOf(daysRemaining))
                .divide(BigDecimal.valueOf(daysElapsed), 0, RoundingMode.CEILING).longValueExact();
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
     * Próximo ciclo a fechar do credor. Com ciclo anterior, percorre os ciclos contíguos a partir do fim do último
     * fechado e para no primeiro que deve gerar extrato (tem consumo ou mensalidade &gt; 0, decisão 624) ou que ainda
     * está em curso; ciclos encerrados sem consumo e sem mensalidade são pulados (sem extrato). Sem ciclo anterior, o
     * primeiro ciclo é o que contém o primeiro consumo do credor. Vazio quando o credor nunca consumiu.
     */
    private Optional<Pending> pendingCycle(UUID creditorId, Instant now) {
        Optional<UsageStatement> last = statements.findLastCycle(creditorId);
        if (last.isEmpty()) {
            return usage.firstUsageAt(creditorId, Instant.EPOCH)
                    .map(first -> cycleStartingAt(creditorId, nominalStart(creditorId, LocalDate.ofInstant(first, ZONE))));
        }
        Pending candidate = cycleStartingAt(creditorId, last.get().getPeriodEnd().plusDays(1));
        while (candidate.cycle().endedAt(now, ZONE) && !generatesStatement(creditorId, candidate)) {
            candidate = cycleStartingAt(creditorId, candidate.cycle().end().plusDays(1));
        }
        return Optional.of(candidate);
    }

    /** Ciclo gera extrato quando tem consumo ou mensalidade maior que zero (decisão 624). */
    private boolean generatesStatement(UUID creditorId, Pending p) {
        if (p.pricing().map(v -> v.monthlyFee().signum() > 0).orElse(false)) {
            return true;
        }
        Instant to = p.cycle().to(ZONE);
        return usage.firstUsageAt(creditorId, p.cycle().from(ZONE)).filter(t -> t.isBefore(to)).isPresent();
    }

    /** Início do ciclo que contém o dia do primeiro consumo, no calendário do dia de fechamento que vale nesse dia. */
    private LocalDate nominalStart(UUID creditorId, LocalDate firstUsageDay) {
        int closingDay = applicablePricing(creditorId, firstUsageDay).map(IntegrationPricingView::usageClosingDay)
                .orElse(ContractIntegrationPricingUseCases.DEFAULT_USAGE_CLOSING_DAY);
        return BillingCycles.containing(firstUsageDay, closingDay).start();
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
                cycle.closingDay(), c.proration, applied, c.totals.requests(), c.billable, c.result.total(), json.toJson(c.lines),
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
                .attr("cycleDays", c.proration.cycleDays()).attr("referenceCycleDays", c.proration.referenceDays())
                .attr("requests", c.totals.requests()).attr("billableRequests", c.billable.requests())
                .attr("clientErrors", c.totals.clientErrors()).attr("throttled", c.totals.throttled())
                .attr("serverErrors", c.totals.serverErrors()).attr("billableMbIn", c.billable.mbIn())
                .attr("billableMbOut", c.billable.mbOut()).attr("records", c.billable.records())
                .attr("contractNumber", statement.getContractNumber())
                .attr("pricingVersion", statement.getPricingVersion()));
        return view(statement);
    }

    // ---------------------------------------------------------------- cálculo

    private record Computed(UsageTotals totals, BillableUsage billable, Proration proration,
                            PriceCalculator.Result result, List<StatementLine> lines, List<StatementBreakdown> breakdown,
                            String usageHash) {
    }

    private Computed compute(UUID creditorId, Pending pending) {
        Cycle cycle = pending.cycle();
        UsageStore.Filter filter = new UsageStore.Filter(cycle.from(ZONE), cycle.to(ZONE), null, creditorId, null);
        UsageTotals totals = usage.totals(filter);
        List<UsageStore.TokenRow> byToken = usage.byToken(filter);
        BillableUsage billable = BillableUsage.sumOfTokens(byToken.stream().map(UsageStore.TokenRow::totals).toList());
        PriceCalculator.Result result = pending.pricing()
                .map(p -> PriceCalculator.calculate(terms(p), billable, BillingCycles.proration(cycle)))
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
        return new Computed(totals, billable, BillingCycles.proration(cycle), result, lines, breakdown,
                sha256(json.toJson(hashed)));
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
        Proration proration = s.getCycleDays() == null ? BillingCycles.proration(cycle)
                : new Proration(s.getCycleDays(), s.getReferenceCycleDays());
        return new StatementView(s.getId(), s.getCreditorId(), s.getPeriodStart(), s.getPeriodEnd(),
                cycle.endedAt(now, ZONE), s.getRevision(), s.getUsageClosingDay(), proration.cycleDays(),
                proration.referenceDays(), s.getContractId(),
                s.getContractNumber(), s.getPricingId(), s.getPricingVersion(), s.getRequests(), s.getBillableRequests(),
                s.getBillableMbIn(), s.getBillableMbOut(), s.getRecords(), s.getTotalAmount(), s.getCurrency(),
                json.fromJson(s.getLines(), LINES), json.fromJson(s.getBreakdown(), BREAKDOWN), s.getUsageHash(),
                s.getClosedAt(), s.getClosedBy(), s.getReason());
    }
}
