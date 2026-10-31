package br.com.gestorcobranca.analytics.application.service;

import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries;
import br.com.gestorcobranca.analytics.application.port.out.AnalyticsReader;
import br.com.gestorcobranca.analytics.application.port.out.AnalyticsReader.CasePositionRow;
import br.com.gestorcobranca.analytics.application.port.out.AnalyticsReader.CohortRaw;
import br.com.gestorcobranca.analytics.application.port.out.AnalyticsReader.EffortRaw;
import br.com.gestorcobranca.analytics.application.port.out.AnalyticsReader.FreshnessRaw;
import br.com.gestorcobranca.analytics.application.port.out.AnalyticsReader.PositionRow;
import br.com.gestorcobranca.analytics.application.port.out.AnalyticsReader.RecoveryRaw;
import br.com.gestorcobranca.analytics.application.port.out.AnalyticsReader.ReliabilityRaw;
import br.com.gestorcobranca.analytics.application.port.out.AnalyticsReader.StatementCost;
import br.com.gestorcobranca.analytics.application.port.out.AnalyticsReader.TeamRaw;
import br.com.gestorcobranca.analytics.application.port.out.AnalyticsReader.TimeToReceiptRaw;
import br.com.gestorcobranca.analytics.application.port.out.Slice;
import br.com.gestorcobranca.analytics.application.service.AnalyticsAccess.PeopleScope;
import br.com.gestorcobranca.analytics.domain.model.Dimension;
import br.com.gestorcobranca.analytics.domain.model.Ratio;
import br.com.gestorcobranca.analytics.domain.model.ReportFilter;
import br.com.gestorcobranca.analytics.domain.service.Indicators;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;

import static br.com.gestorcobranca.analytics.domain.service.Indicators.ratio;

/**
 * Montagem dos painéis: aplica escopo (credores e pessoas), lê medidas aditivas e calcula os
 * indicadores com denominador explícito (docs/negocio/indicadores.md).
 */
@Service
@Transactional(readOnly = true)
class AnalyticsQueryService implements AnalyticsQueries {

    static final Set<Dimension> RECOVERY_DIMENSIONS = EnumSet.allOf(Dimension.class);
    static final Set<Dimension> CONVERSION_DIMENSIONS = EnumSet.complementOf(EnumSet.of(Dimension.DELAY_BUCKET));
    static final Set<Dimension> PRODUCTIVITY_DIMENSIONS = EnumSet.of(Dimension.NONE, Dimension.OPERATOR, Dimension.TEAM);
    /** Chave de agrupamento sem valor (ex.: recebimento sem negociador humano). */
    static final String NO_KEY = "-";
    static final List<Integer> BREAK_WINDOWS = List.of(30, 60, 90);

    /** Mesmas permissões que dão escopo de pessoas ({@link AnalyticsAccess#peopleScope()}); só relatório não basta. */
    private static final Permission[] PEOPLE_PERMISSIONS = {Permission.PERFORMANCE_VIEW_OWN,
            Permission.PERFORMANCE_VIEW_TEAM, Permission.PERFORMANCE_VIEW_ALL, Permission.COMMISSION_STATEMENT_MANAGE,
            Permission.COMMISSION_STATEMENT_APPROVE};

    private final AnalyticsReader reader;
    private final AnalyticsAccess access;
    private final AnalyticsMetrics metrics;
    private final Clock clock;

    AnalyticsQueryService(AnalyticsReader reader, AnalyticsAccess access, AnalyticsMetrics metrics, Clock clock) {
        this.reader = reader;
        this.access = access;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Recorte efetivo e contexto de pessoas de uma consulta. */
    private record Context(Slice slice, boolean peopleRestricted, List<TeamRaw> teams, Set<UUID> visibleTeams) {
    }

    // ================================================================ carteira

    @Override
    public PortfolioReport portfolio(ReportFilter filter) {
        access.requireAny("analytics_portfolio", Permission.DASHBOARD_VIEW, Permission.REPORT_VIEW);
        return metrics.query("portfolio", "NONE", () -> {
            Context ctx = context(filter, false);
            List<String> notApplicable = new ArrayList<>(List.of("from", "to"));
            notApplicableIfSet(notApplicable, filter);
            if (!filter.channels().isEmpty()) {
                notApplicable.add("channels");
            }
            List<PositionRow> titles = reader.titlePosition(ctx.slice());
            List<CasePositionRow> cases = reader.casePosition(ctx.slice());

            Map<String, String> creditorLabels = reader.labels(Dimension.CREDITOR,
                    keys(titles, cases, r -> r.creditorId().toString(), r -> r.creditorId().toString()));
            Map<String, String> portfolioLabels = reader.labels(Dimension.PORTFOLIO,
                    keys(titles, cases, r -> r.portfolioId().toString(), r -> r.portfolioId().toString()));
            List<PortfolioRow> byCreditor = portfolioRows(titles, cases, r -> r.creditorId().toString(),
                    r -> r.creditorId().toString(), creditorLabels);
            List<PortfolioRow> byPortfolio = portfolioRows(titles, cases, r -> r.portfolioId().toString(),
                    r -> r.portfolioId().toString(), portfolioLabels);
            List<PortfolioRow> byBucket = portfolioRows(titles, cases, PositionRow::delayBucket,
                    CasePositionRow::delayBucket, Map.of());
            byBucket.sort(Comparator.comparingInt(r -> BUCKET_ORDER.indexOf(r.key())));

            Map<String, long[]> statusCounts = new LinkedHashMap<>();
            Map<String, BigDecimal[]> statusAmounts = new LinkedHashMap<>();
            Map<String, Long> queueCases = new LinkedHashMap<>();
            Map<String, BigDecimal> queueBalance = new LinkedHashMap<>();
            for (CasePositionRow c : cases) {
                long[] count = statusCounts.computeIfAbsent(c.status(), k -> new long[2]);
                count[0] += c.cases();
                count[1] += c.eligible() ? c.cases() : 0;
                BigDecimal[] amount = statusAmounts.computeIfAbsent(c.status(),
                        k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                amount[0] = amount[0].add(c.openBalance());
                amount[1] = amount[1].add(c.eligibleBalance());
                if (c.eligible() && "QUEUE".equals(c.ownerType())) {
                    String q = c.queueId() == null ? NO_KEY : c.queueId().toString();
                    queueCases.merge(q, c.cases(), Long::sum);
                    queueBalance.merge(q, c.eligibleBalance(), BigDecimal::add);
                }
            }
            List<CaseStatusRow> byStatus = statusCounts.entrySet().stream()
                    .map(e -> new CaseStatusRow(e.getKey(), e.getValue()[0], statusAmounts.get(e.getKey())[0],
                            statusAmounts.get(e.getKey())[1], e.getValue()[1]))
                    .sorted(Comparator.comparingInt(r -> STATUS_ORDER.indexOf(r.status()))).toList();
            Map<String, String> queueLabels = reader.queueLabels(queueCases.keySet());
            List<QueueRow> pending = queueCases.entrySet().stream()
                    .map(e -> new QueueRow(e.getKey(), queueLabels.getOrDefault(e.getKey(), "Sem fila"), e.getValue(),
                            queueBalance.get(e.getKey())))
                    .sorted(Comparator.comparingLong(QueueRow::cases).reversed()).toList();

            BigDecimal portfolioAmount = sum(titles, PositionRow::portfolioAmount);
            BigDecimal overdue = sum(titles, PositionRow::overdueAmount);
            BigDecimal caseOpen = sum(cases, CasePositionRow::openBalance);
            BigDecimal eligible = sum(cases, CasePositionRow::eligibleBalance);
            long openCases = cases.stream().mapToLong(CasePositionRow::cases).sum();
            long eligibleCases = cases.stream().filter(CasePositionRow::eligible).mapToLong(CasePositionRow::cases).sum();
            long pendingCases = queueCases.values().stream().mapToLong(Long::longValue).sum();
            PortfolioTotals totals = new PortfolioTotals(portfolioAmount,
                    titles.stream().mapToLong(PositionRow::openTitles).sum(), overdue,
                    titles.stream().mapToLong(PositionRow::overdueTitles).sum(), ratio(overdue, portfolioAmount),
                    caseOpen, eligible, ratio(eligible, caseOpen), openCases, eligibleCases, pendingCases,
                    sum(queueBalance.values()), ratio(pendingCases, eligibleCases));
            ReportMeta meta = meta(filter, "NONE", dataUntil("portfolio_position", "case_position"), notApplicable,
                    ctx.peopleRestricted(), List.of());
            return new PortfolioReport(meta, reader.positionReferenceDate(), totals, byCreditor, byPortfolio, byBucket,
                    byStatus, pending);
        });
    }

    static final List<String> BUCKET_ORDER = List.of("NOT_DUE", "D0_30", "D31_90", "D91_180", "D181_360", "D361_PLUS",
            "UNKNOWN");
    static final List<String> STATUS_ORDER = List.of("OPEN", "IN_NEGOTIATION", "PROMISE", "AGREEMENT", "DISPUTED",
            "SUSPENDED", "LEGAL");

    private static List<PortfolioRow> portfolioRows(List<PositionRow> titles, List<CasePositionRow> cases,
                                                    Function<PositionRow, String> titleKey,
                                                    Function<CasePositionRow, String> caseKey,
                                                    Map<String, String> labels) {
        Map<String, BigDecimal[]> amounts = new LinkedHashMap<>();
        Map<String, long[]> counts = new LinkedHashMap<>();
        for (PositionRow t : titles) {
            String k = titleKey.apply(t);
            BigDecimal[] a = amounts.computeIfAbsent(k, x -> zeros(3));
            a[0] = a[0].add(t.portfolioAmount());
            a[1] = a[1].add(t.overdueAmount());
            counts.computeIfAbsent(k, x -> new long[3])[0] += t.openTitles();
        }
        for (CasePositionRow c : cases) {
            String k = caseKey.apply(c);
            BigDecimal[] a = amounts.computeIfAbsent(k, x -> zeros(3));
            a[2] = a[2].add(c.eligibleBalance());
            long[] n = counts.computeIfAbsent(k, x -> new long[3]);
            n[1] += c.cases();
            n[2] += c.eligible() ? c.cases() : 0;
        }
        List<PortfolioRow> rows = new ArrayList<>();
        amounts.forEach((k, a) -> rows.add(new PortfolioRow(k, labels.getOrDefault(k, k), a[0], counts.get(k)[0], a[1],
                ratio(a[1], a[0]), a[2], counts.get(k)[1], counts.get(k)[2])));
        rows.sort(Comparator.comparing(PortfolioRow::portfolioAmount).reversed());
        return rows;
    }

    // ============================================================= recuperação

    @Override
    public RecoveryReport recovery(ReportFilter filter, String groupBy) {
        access.requireAny("analytics_recovery", Permission.REPORT_VIEW);
        Dimension d = Dimension.parse(groupBy, RECOVERY_DIMENSIONS);
        return metrics.query("recovery", d.name(), () -> {
            Context ctx = context(filter, d.byPerson() || hasPeopleFilter(filter));
            Slice s = ctx.slice();
            boolean denominatorSegmentable = s.channels() == null;
            RecoveryRaw total = reader.recovery(s, Dimension.NONE).stream().findFirst()
                    .orElse(new RecoveryRaw("ALL", BigDecimal.ZERO, BigDecimal.ZERO, 0));
            Ratio totalRate = denominatorSegmentable
                    ? recoveryRate(total, reader.eligibleAtStart(s, Dimension.NONE).get("ALL"))
                    : Ratio.unavailable("not_segmentable");
            RecoveryTotals totals = new RecoveryTotals(total.gross(), total.reversed(),
                    Indicators.net(total.gross(), total.reversed()), total.receipts(), totalRate,
                    ratio(total.reversed(), total.gross()));

            List<RecoveryRow> rows = List.of();
            if (d != Dimension.NONE) {
                Dimension readDim = d == Dimension.TEAM ? Dimension.OPERATOR : d;
                List<RecoveryRaw> raw = reader.recovery(s, readDim);
                boolean rowRates = denominatorSegmentable && EnumSet.of(Dimension.CREDITOR, Dimension.PORTFOLIO,
                        Dimension.OPERATOR, Dimension.TEAM).contains(d);
                Map<String, BigDecimal> eligible = rowRates ? reader.eligibleAtStart(s, readDim) : Map.of();
                if (d == Dimension.TEAM) {
                    raw = rollUp(raw, ctx, RecoveryRaw::key, (k, list) -> new RecoveryRaw(k,
                            sum(list, RecoveryRaw::gross), sum(list, RecoveryRaw::reversed),
                            list.stream().mapToLong(RecoveryRaw::receipts).sum()));
                    eligible = rollUpAmounts(eligible, ctx);
                }
                Map<String, String> labels = labels(d, raw.stream().map(RecoveryRaw::key).toList(), ctx);
                Map<String, BigDecimal> eligibleByKey = eligible;
                rows = raw.stream().map(r -> new RecoveryRow(r.key(), labels.getOrDefault(r.key(), r.key()), r.gross(),
                        r.reversed(), Indicators.net(r.gross(), r.reversed()), r.receipts(),
                        rowRates ? recoveryRate(r, eligibleByKey.get(r.key())) : Ratio.unavailable("not_segmentable")))
                        .sorted(d.temporal() ? Comparator.comparing(RecoveryRow::key)
                                : Comparator.comparing(RecoveryRow::recoveredNet).reversed())
                        .toList();
            }
            TimeToReceiptRaw ttr = reader.timeToReceipt(s);
            ReportMeta meta = meta(filter, d.name(), dataUntil("recovery_daily", "agreement_cohort"), List.of(),
                    ctx.peopleRestricted(), List.of("cost.channelCosts"));
            return new RecoveryReport(meta, totals, rows, new TimeToReceipt(ttr.agreements(), ttr.averageDays(),
                    ttr.medianDays(), ttr.p90Days()), cost(filter, s));
        });
    }

    private static Ratio recoveryRate(RecoveryRaw r, BigDecimal eligibleAtStart) {
        return ratio(Indicators.net(r.gross(), r.reversed()), eligibleAtStart);
    }

    /**
     * Custo por real recuperado: comissão variável dos extratos aprovados/exportados ÷ recuperado
     * líquido, nos meses inteiros do período; só sem recortes (extratos não se dividem por credor,
     * carteira, canal ou pessoa), para quem vê todos e com ≥ 3 extratos (agregado não identifica a pessoa).
     */
    private CostPerRecovered cost(ReportFilter filter, Slice s) {
        LocalDate first = filter.from().getDayOfMonth() == 1 ? filter.from()
                : filter.from().withDayOfMonth(1).plusMonths(1);
        LocalDate last = filter.to().getDayOfMonth() == filter.to().lengthOfMonth() ? filter.to().withDayOfMonth(1)
                : filter.to().withDayOfMonth(1).minusMonths(1);
        if (first.isAfter(last)) {
            return new CostPerRecovered(Ratio.unavailable("no_full_month"), null, null, 0);
        }
        boolean sliced = s.creditors() != null || s.portfolios() != null || s.channels() != null
                || s.operators() != null;
        if (sliced) {
            return new CostPerRecovered(Ratio.unavailable("not_segmentable"), first, last, 0);
        }
        if (access.peopleScope() != PeopleScope.ALL) {
            return new CostPerRecovered(Ratio.unavailable("out_of_scope"), first, last, 0);
        }
        StatementCost cost = reader.statementCost(first, last);
        if (cost.statements() == 0) {
            return new CostPerRecovered(Ratio.unavailable("no_source"), first, last, 0);
        }
        if (cost.statements() < 3) {
            return new CostPerRecovered(Ratio.unavailable("privacy_minimum"), first, last, cost.statements());
        }
        LocalDate lastDay = last.withDayOfMonth(last.lengthOfMonth());
        Slice months = new Slice(first, lastDay, start(first), start(lastDay.plusDays(1)), null, null, null, null);
        RecoveryRaw recovered = reader.recovery(months, Dimension.NONE).stream().findFirst()
                .orElse(new RecoveryRaw("ALL", BigDecimal.ZERO, BigDecimal.ZERO, 0));
        return new CostPerRecovered(ratio(cost.payable(), Indicators.net(recovered.gross(), recovered.reversed())),
                first, last, cost.statements());
    }

    // =============================================================== conversão

    @Override
    public ConversionReport conversion(ReportFilter filter, String groupBy) {
        access.requireAny("analytics_conversion", Permission.REPORT_VIEW);
        Dimension d = Dimension.parse(groupBy, CONVERSION_DIMENSIONS);
        return metrics.query("conversion", d.name(), () -> {
            Context ctx = context(filter, d.byPerson() || hasPeopleFilter(filter));
            Slice s = ctx.slice();
            Instant asOf = Instant.now(clock);
            long contacts = nzl(reader.effectiveContacts(s, Dimension.NONE).get("ALL"));
            long proposals = nzl(reader.proposals(s, Dimension.NONE).get("ALL"));
            CohortRaw total = reader.cohort(s, Dimension.NONE, asOf).stream().findFirst().orElse(emptyCohort("ALL"));
            Funnel funnel = new Funnel(contacts, proposals, total.agreements(), total.firstInstallmentsPaid(),
                    ratio(proposals, contacts), ratio(total.agreements(), proposals),
                    ratio(total.firstInstallmentsPaid(), total.agreements()), ratio(total.agreements(), contacts));
            AnalyticsReader.InstallmentsRaw installments = reader.installmentsPaid(s);
            CohortQuality quality = new CohortQuality(total.agreements(), total.agreedAmount(), total.paidAmount(),
                    ratio(total.paidAmount(), total.agreedAmount()), installments.count(), installments.amount(),
                    total.renegotiations(), ratio(total.renegotiations(), total.agreements()), breaks(total));

            List<ConversionRow> rows = List.of();
            if (d != Dimension.NONE) {
                Dimension readDim = d == Dimension.TEAM ? Dimension.OPERATOR : d;
                List<CohortRaw> cohort = reader.cohort(s, readDim, asOf);
                Map<String, Long> contactsBy = reader.effectiveContacts(s, readDim);
                Map<String, Long> proposalsBy = reader.proposals(s, readDim);
                if (d == Dimension.TEAM) {
                    cohort = rollUp(cohort, ctx, CohortRaw::key, AnalyticsQueryService::sumCohort);
                    contactsBy = rollUpCounts(contactsBy, ctx);
                    proposalsBy = rollUpCounts(proposalsBy, ctx);
                }
                Set<String> keys = new LinkedHashSet<>();
                cohort.forEach(c -> keys.add(c.key()));
                if (contactsBy != null) {
                    keys.addAll(contactsBy.keySet());
                }
                if (proposalsBy != null) {
                    keys.addAll(proposalsBy.keySet());
                }
                Map<String, CohortRaw> cohortBy = cohort.stream().collect(Collectors.toMap(CohortRaw::key, c -> c));
                Map<String, String> labels = labels(d, keys, ctx);
                Map<String, Long> contactsMap = contactsBy;
                Map<String, Long> proposalsMap = proposalsBy;
                rows = keys.stream().map(k -> {
                    CohortRaw c = cohortBy.getOrDefault(k, emptyCohort(k));
                    Long kc = contactsMap == null ? null : nzl(contactsMap.get(k));
                    Long kp = proposalsMap == null ? null : nzl(proposalsMap.get(k));
                    return new ConversionRow(k, labels.getOrDefault(k, k), kc, kp, c.agreements(), c.agreedAmount(),
                            c.paidAmount(), ratio(c.paidAmount(), c.agreedAmount()), c.firstInstallmentsPaid(),
                            ratio(c.firstInstallmentsPaid(), c.agreements()),
                            kc == null ? Ratio.unavailable("not_segmentable") : ratio(c.agreements(), kc));
                }).sorted(d.temporal() ? Comparator.comparing(ConversionRow::key)
                        : Comparator.comparingLong(ConversionRow::agreements).reversed()).toList();
            }
            ReportMeta meta = meta(filter, d.name(), dataUntil("agreement_cohort"), List.of(), ctx.peopleRestricted(),
                    List.of());
            return new ConversionReport(meta, funnel, quality, rows);
        });
    }

    private static List<BreakRate> breaks(CohortRaw c) {
        return List.of(new BreakRate(30, c.matured30(), c.broken30(), ratio(c.broken30(), c.matured30())),
                new BreakRate(60, c.matured60(), c.broken60(), ratio(c.broken60(), c.matured60())),
                new BreakRate(90, c.matured90(), c.broken90(), ratio(c.broken90(), c.matured90())));
    }

    private static CohortRaw emptyCohort(String key) {
        return new CohortRaw(key, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static CohortRaw sumCohort(String key, List<CohortRaw> list) {
        return new CohortRaw(key, list.stream().mapToLong(CohortRaw::agreements).sum(), sum(list, CohortRaw::agreedAmount),
                sum(list, CohortRaw::paidAmount), list.stream().mapToLong(CohortRaw::firstInstallmentsPaid).sum(),
                list.stream().mapToLong(CohortRaw::renegotiations).sum(), list.stream().mapToLong(CohortRaw::matured30).sum(),
                list.stream().mapToLong(CohortRaw::broken30).sum(), list.stream().mapToLong(CohortRaw::matured60).sum(),
                list.stream().mapToLong(CohortRaw::broken60).sum(), list.stream().mapToLong(CohortRaw::matured90).sum(),
                list.stream().mapToLong(CohortRaw::broken90).sum());
    }

    // ============================================================ produtividade

    @Override
    public ProductivityReport productivity(ReportFilter filter, String groupBy) {
        access.requireAny("analytics_productivity", PEOPLE_PERMISSIONS);
        Dimension d = Dimension.parse(groupBy, PRODUCTIVITY_DIMENSIONS);
        return metrics.query("productivity", d.name(), () -> {
            Context ctx = context(filter, true);
            Slice s = ctx.slice();
            Map<UUID, EffortRaw> effort = reader.effort(s).stream().collect(Collectors.toMap(EffortRaw::userId, e -> e));
            Map<UUID, Long> logged = reader.loggedSeconds(s);
            Map<UUID, Long> agreements = reader.agreementsByOperator(s);
            Map<UUID, BigDecimal> recovered = reader.recoveredByOperator(s);
            Set<UUID> users = new LinkedHashSet<>(effort.keySet());
            users.addAll(agreements.keySet());
            users.addAll(recovered.keySet());
            // tempo logado sozinho não faz linha (pessoa sem atuação no recorte)
            boolean timeComparable = !filter.segmentsBeyondPeople();
            if (timeComparable) {
                users.addAll(logged.keySet());
            }
            Map<String, PersonTotals> people = new LinkedHashMap<>();
            for (UUID u : users) {
                EffortRaw e = effort.getOrDefault(u, new EffortRaw(u, 0, 0, 0, 0, 0));
                people.put(u.toString(), new PersonTotals(e.workedCases(), e.attempts(), e.effectiveContacts(),
                        e.answeredCalls(), e.talkSeconds(), logged.getOrDefault(u, 0L), agreements.getOrDefault(u, 0L),
                        recovered.getOrDefault(u, BigDecimal.ZERO)));
            }
            PersonTotals all = people.values().stream().reduce(PersonTotals.ZERO, PersonTotals::plus);
            Map<String, PersonTotals> grouped = switch (d) {
                case OPERATOR -> people;
                case TEAM -> rollUpMap(people, ctx, PersonTotals::plus);
                default -> Map.of();
            };
            Map<String, String> labels = labels(d, grouped.keySet(), ctx);
            List<ProductivityRow> rows = grouped.entrySet().stream()
                    .map(e -> e.getValue().row(e.getKey(), labels.getOrDefault(e.getKey(), e.getKey()), timeComparable))
                    .sorted(Comparator.comparing(ProductivityRow::recoveredNet).reversed()
                            .thenComparing(ProductivityRow::label))
                    .toList();
            List<String> notApplicable = timeComparable ? List.of()
                    : List.of("loggedTime:creditorIds,portfolioIds,channels");
            ReportMeta meta = meta(filter, d.name(), dataUntil("recovery_daily", "agreement_cohort"), notApplicable,
                    ctx.peopleRestricted(), List.of());
            return new ProductivityReport(meta, all.row("ALL", "Total", timeComparable), rows);
        });
    }

    /** Medidas aditivas por pessoa (somáveis por equipe e no total). */
    record PersonTotals(long workedCases, long attempts, long effectiveContacts, long answeredCalls, long talkSeconds,
                        long loggedSeconds, long agreements, BigDecimal recovered) {

        static final PersonTotals ZERO = new PersonTotals(0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO);

        PersonTotals plus(PersonTotals o) {
            return new PersonTotals(workedCases + o.workedCases, attempts + o.attempts,
                    effectiveContacts + o.effectiveContacts, answeredCalls + o.answeredCalls, talkSeconds + o.talkSeconds,
                    loggedSeconds + o.loggedSeconds, agreements + o.agreements, recovered.add(o.recovered));
        }

        ProductivityRow row(String key, String label, boolean timeComparable) {
            return new ProductivityRow(key, label, workedCases, attempts, effectiveContacts,
                    ratio(effectiveContacts, attempts), answeredCalls, talkSeconds, ratio(talkSeconds, answeredCalls),
                    Indicators.hours(loggedSeconds), agreements,
                    timeComparable ? Indicators.perHour(agreements, loggedSeconds) : Ratio.unavailable("not_segmentable"),
                    recovered);
        }
    }

    // =========================================================== confiabilidade

    @Override
    public ReliabilityReport reliability(ReportFilter filter) {
        access.requireAny("analytics_reliability", Permission.DASHBOARD_VIEW, Permission.REPORT_VIEW);
        return metrics.query("reliability", "NONE", () -> {
            Context ctx = context(filter, false);
            List<String> notApplicable = new ArrayList<>();
            notApplicableIfSet(notApplicable, filter);
            if (!filter.portfolioIds().isEmpty()) {
                notApplicable.add("portfolioIds:exceto importações");
            }
            ReliabilityRaw r = reader.reliability(ctx.slice());
            boolean jobsVisible = ctx.slice().creditors() == null;
            List<String> unavailable = new ArrayList<>(List.of("messages.duplicates"));
            if (!jobsVisible) {
                unavailable.add("jobs:requer acesso a todos os credores");
            }
            IntegrationHealth integrations = new IntegrationHealth(r.instructions(), r.instructionsFailed(),
                    ratio(r.instructionsFailed(), r.instructions()), countRows(r.instructionFailures()), r.calls(),
                    r.callsFailed(), ratio(r.callsFailed(), r.calls()), r.statementImports(), r.statementImportsFailed());
            MessageHealth messages = new MessageHealth(r.outbound(), r.messagesFailed(),
                    ratio(r.messagesFailed(), r.outbound()), countRows(r.messageFailures()), r.statementLines(),
                    r.duplicateLines(), ratio(r.duplicateLines(), r.statementLines()), r.duplicatePaymentDivergences(),
                    r.slaBreaches());
            ReconciliationHealth reconciliation = new ReconciliationHealth(r.divergencesOpened(),
                    countRows(r.divergencesByType()), r.divergencesOpenNow(), r.divergencesOpenAmount(),
                    r.divergencesResolved(), r.medianResolutionHours());
            JobHealth jobs = jobsVisible ? new JobHealth(r.deadNow(), countRows(r.deadByType()), r.jobsFinished(),
                    r.jobsDead(), ratio(r.jobsDead(), r.jobsFinished())) : null;
            ImportHealth imports = new ImportHealth(r.batches(), r.batchesFailed(), ratio(r.batchesFailed(), r.batches()),
                    r.batchMedianSeconds(), r.batchP95Seconds(), r.rowsRead(), r.rowsWithError(),
                    ratio(r.rowsWithError(), r.rowsRead()));
            ReportMeta meta = meta(filter, "NONE", Instant.now(clock), notApplicable, false, unavailable);
            return new ReliabilityReport(meta, integrations, messages, reconciliation, jobs, imports);
        });
    }

    @Override
    public List<DatasetFreshness> freshness() {
        access.requireAny("analytics_freshness", Permission.DASHBOARD_VIEW, Permission.REPORT_VIEW,
                Permission.PERFORMANCE_VIEW_OWN, Permission.PERFORMANCE_VIEW_TEAM, Permission.PERFORMANCE_VIEW_ALL);
        return reader.freshness().stream().map(f -> new DatasetFreshness(f.dataset(), f.dataUntil(), f.refreshedAt(),
                f.durationMs(), f.rowCount())).toList();
    }

    // ================================================================== escopo

    private Context context(ReportFilter filter, boolean personal) {
        Set<UUID> creditors = access.creditors(filter.creditorIds());
        Set<UUID> portfolios = filter.portfolioIds().isEmpty() ? null : filter.portfolioIds();
        Set<String> channels = filter.channels().isEmpty() ? null : filter.channels();
        List<TeamRaw> teams = List.of();
        Set<UUID> operators = null;
        boolean restricted = false;
        Set<UUID> visibleTeams = null;
        if (personal) {
            PeopleScope scope = access.requirePeopleScope();
            teams = reader.teams();
            Set<UUID> visible = access.visibleUsers(scope, teams);
            visibleTeams = access.visibleTeams(scope, teams);
            if (visibleTeams != null && !visibleTeams.containsAll(filter.teamIds())) {
                access.denyScope("analytics_team", "team_outside_scope");
            }
            if (visible != null && !visible.containsAll(filter.operatorIds())) {
                access.denyScope("analytics_operator", "operator_outside_scope");
            }
            operators = requestedPeople(filter, teams);
            if (visible != null) {
                operators = operators == null ? visible : operators;
                restricted = true;
            }
        }
        LocalDate from = filter.from();
        LocalDate to = filter.to();
        return new Context(new Slice(from, to, start(from), start(to.plusDays(1)), creditors, portfolios, operators,
                channels), restricted, teams, visibleTeams);
    }

    /** Operadores pedidos: filtro por pessoa, membros das equipes pedidas, ou a interseção dos dois. */
    private static Set<UUID> requestedPeople(ReportFilter filter, List<TeamRaw> teams) {
        Set<UUID> fromTeams = null;
        if (!filter.teamIds().isEmpty()) {
            fromTeams = teams.stream().filter(t -> filter.teamIds().contains(t.teamId()))
                    .flatMap(t -> t.members().stream()).collect(Collectors.toSet());
        }
        if (filter.operatorIds().isEmpty()) {
            return fromTeams;
        }
        if (fromTeams == null) {
            return filter.operatorIds();
        }
        Set<UUID> both = new HashSet<>(filter.operatorIds());
        both.retainAll(fromTeams);
        return both;
    }

    private static boolean hasPeopleFilter(ReportFilter filter) {
        return !filter.operatorIds().isEmpty() || !filter.teamIds().isEmpty();
    }

    private static void notApplicableIfSet(List<String> notApplicable, ReportFilter filter) {
        if (!filter.operatorIds().isEmpty()) {
            notApplicable.add("operatorIds");
        }
        if (!filter.teamIds().isEmpty()) {
            notApplicable.add("teamIds");
        }
    }

    // ======================================================== equipes (rollup)

    /** Equipes do agrupamento: todas (escopo total) ou as visíveis; inclui as que têm membros no recorte. */
    private List<TeamRaw> groupTeams(Context ctx) {
        return ctx.teams().stream().filter(TeamRaw::active)
                .filter(t -> ctx.visibleTeams() == null || ctx.visibleTeams().contains(t.teamId())
                        || (ctx.slice().operators() != null && t.members().stream().anyMatch(ctx.slice().operators()::contains)))
                .toList();
    }

    private <T> List<T> rollUp(List<T> byOperator, Context ctx, Function<T, String> key,
                               java.util.function.BiFunction<String, List<T>, T> merge) {
        Map<String, T> byKey = byOperator.stream().collect(Collectors.toMap(key, x -> x, (a, b) -> a, LinkedHashMap::new));
        List<T> result = new ArrayList<>();
        for (TeamRaw team : groupTeams(ctx)) {
            List<T> members = team.members().stream().map(UUID::toString).map(byKey::get).filter(Objects::nonNull)
                    .toList();
            if (!members.isEmpty()) {
                result.add(merge.apply(team.teamId().toString(), members));
            }
        }
        return result;
    }

    private <T> Map<String, T> rollUpMap(Map<String, T> byOperator, Context ctx, BinaryOperator<T> plus) {
        Map<String, T> result = new LinkedHashMap<>();
        for (TeamRaw team : groupTeams(ctx)) {
            team.members().stream().map(UUID::toString).map(byOperator::get).filter(Objects::nonNull)
                    .reduce(plus).ifPresent(v -> result.put(team.teamId().toString(), v));
        }
        return result;
    }

    private Map<String, BigDecimal> rollUpAmounts(Map<String, BigDecimal> byOperator, Context ctx) {
        return rollUpMap(byOperator, ctx, BigDecimal::add);
    }

    private Map<String, Long> rollUpCounts(Map<String, Long> byOperator, Context ctx) {
        return byOperator == null ? null : rollUpMap(byOperator, ctx, Long::sum);
    }

    // ================================================================= apoio

    private Map<String, String> labels(Dimension d, Collection<String> keys, Context ctx) {
        Map<String, String> labels = new HashMap<>();
        if (d == Dimension.TEAM) {
            ctx.teams().forEach(t -> labels.put(t.teamId().toString(), t.name()));
        } else {
            labels.putAll(reader.labels(d, keys));
        }
        if (keys.contains(NO_KEY)) {
            labels.put(NO_KEY, switch (d) {
                case OPERATOR, TEAM -> "Sem negociador humano";
                case PORTFOLIO -> "Sem carteira";
                default -> "Não informado";
            });
        }
        return labels;
    }

    private Instant dataUntil(String... datasets) {
        Map<String, Instant> states = reader.freshness().stream().filter(f -> f.dataUntil() != null)
                .collect(Collectors.toMap(FreshnessRaw::dataset, FreshnessRaw::dataUntil));
        Instant min = null;
        for (String dataset : datasets) {
            Instant at = states.get(dataset);
            if (at == null) {
                return null;
            }
            min = min == null || at.isBefore(min) ? at : min;
        }
        return min;
    }

    private static ReportMeta meta(ReportFilter filter, String groupBy, Instant dataUntil, List<String> notApplicable,
                                   boolean peopleRestricted, List<String> unavailable) {
        return new ReportMeta(filter.from(), filter.to(), groupBy, dataUntil, List.copyOf(notApplicable),
                peopleRestricted, List.copyOf(unavailable));
    }

    static Instant start(LocalDate date) {
        return date.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
    }

    private static List<CountRow> countRows(Map<String, Long> counts) {
        return counts.entrySet().stream().map(e -> new CountRow(e.getKey(), e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(CountRow::count).reversed()).toList();
    }

    private static <T> BigDecimal sum(Collection<T> rows, Function<T, BigDecimal> value) {
        return rows.stream().map(value).map(Indicators::nz).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sum(Collection<BigDecimal> values) {
        return values.stream().map(Indicators::nz).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static <A, B> Set<String> keys(List<A> a, List<B> b, Function<A, String> ka, Function<B, String> kb) {
        Set<String> keys = new LinkedHashSet<>();
        a.forEach(x -> keys.add(ka.apply(x)));
        b.forEach(x -> keys.add(kb.apply(x)));
        return keys;
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] values = new BigDecimal[n];
        java.util.Arrays.fill(values, BigDecimal.ZERO);
        return values;
    }

    private static long nzl(Long value) {
        return value == null ? 0 : value;
    }
}
