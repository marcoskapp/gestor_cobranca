package br.com.gestorcobranca.costing.application.service;

import br.com.gestorcobranca.costing.application.CostingProperties;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries;
import br.com.gestorcobranca.costing.application.port.out.CostFactStore;
import br.com.gestorcobranca.costing.application.port.out.CostFactStore.RefreshState;
import br.com.gestorcobranca.costing.application.port.out.MarginReader;
import br.com.gestorcobranca.costing.application.port.out.MarginReader.ActivityRow;
import br.com.gestorcobranca.costing.application.port.out.MarginReader.CostRow;
import br.com.gestorcobranca.costing.application.port.out.MarginReader.Slice;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries.InvoiceLine;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries.MonthReconciliation;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries.Reconciliation;
import br.com.gestorcobranca.costing.domain.model.ChannelItem;
import br.com.gestorcobranca.costing.domain.model.CostCategory;
import br.com.gestorcobranca.costing.domain.model.CostWarningCode;
import br.com.gestorcobranca.costing.domain.model.ItemEstimate;
import br.com.gestorcobranca.costing.domain.model.MarginBasis;
import br.com.gestorcobranca.costing.domain.model.MarginLine;
import br.com.gestorcobranca.costing.domain.model.MarginTotals;
import br.com.gestorcobranca.costing.domain.model.ReconciliationStatus;
import br.com.gestorcobranca.costing.domain.repository.ChannelInvoiceRepository;
import br.com.gestorcobranca.costing.domain.service.MarginCalculator;
import br.com.gestorcobranca.costing.domain.service.MarginTrendSeries;
import br.com.gestorcobranca.costing.domain.service.MarginTrendSeries.MonthStatus;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Painel de margem (contrato §10.2): soma os fatos apurados no recorte (período, credores do escopo, carteiras) por
 * credor, carteira e mês e aplica as fórmulas do domínio ({@link MarginCalculator}). Na base conciliada (§13), os custos
 * de canal somam o ajuste pela fatura, gravado como fato à parte.
 */
@Service
@Transactional(readOnly = true)
class MarginQueryService implements MarginQueries {

    private final MarginReader reader;
    private final CostFactStore store;
    private final ChannelInvoiceRepository invoices;
    private final AccessScope accessScope;
    private final CostingMetrics metrics;
    private final CostingProperties properties;
    private final Clock clock;

    MarginQueryService(MarginReader reader, CostFactStore store, ChannelInvoiceRepository invoices,
                       AccessScope accessScope, CostingMetrics metrics, CostingProperties properties, Clock clock) {
        this.reader = reader;
        this.store = store;
        this.invoices = invoices;
        this.accessScope = accessScope;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Recorte resolvido: período, escopo e base dos custos de canal. {@code creditorIds} nulo = todos; vazio = nenhum
     * (fora do escopo).
     */
    record Resolved(LocalDate from, LocalDate to, Set<UUID> creditorIds, Set<UUID> portfolioIds,
                    boolean includeUnallocated, MarginBasis basis) {

        Slice slice() {
            return new Slice(from, to, creditorIds, portfolioIds, includeUnallocated);
        }

        boolean empty() {
            return creditorIds != null && creditorIds.isEmpty();
        }
    }

    @Override
    public MarginReport margin(MarginFilter filter) {
        accessScope.requirePermission(Permission.COST_VIEW);
        long start = System.nanoTime();
        String outcome = "error";
        try {
            MarginReport report = build(resolve(filter));
            outcome = "ok";
            return report;
        } finally {
            metrics.queried(outcome, Duration.ofNanos(System.nanoTime() - start));
        }
    }

    @Override
    public MarginTrend trend(MarginFilter filter) {
        accessScope.requirePermission(Permission.COST_VIEW);
        long start = System.nanoTime();
        String outcome = "error";
        try {
            MarginTrend trend = buildTrend(resolve(filter));
            outcome = "ok";
            return trend;
        } finally {
            metrics.trendQueried(outcome, Duration.ofNanos(System.nanoTime() - start));
        }
    }

    /**
     * Mesmo recorte de credores e carteiras do painel, na janela de 6 meses que termina no mês do fim do período; as
     * duas leituras agrupadas (custos e atividade por credor × carteira × mês) são as do painel — sem consulta por
     * credor. A linha sem credor não entra (a tendência é por credor).
     */
    MarginTrend buildTrend(Resolved r) {
        Optional<RefreshState> state = store.state();
        LocalDate computedUntil = state.map(RefreshState::lastDay).orElse(null);
        List<YearMonth> window = MarginTrendSeries.window(YearMonth.from(r.to()));
        List<MonthStatus> statuses = window.stream().map(m -> MarginTrendSeries.status(m, computedUntil)).toList();
        Slice slice = new Slice(window.getFirst().atDay(1), window.getLast().atEndOfMonth(), r.creditorIds(),
                r.portfolioIds(), false);
        // base conciliada: soma o ajuste pela fatura, como no painel (§13)
        List<CostRow> costs = r.empty() ? List.of() : withAdjustments(r, reader.costs(slice),
                r.basis() == MarginBasis.RECONCILED ? reader.invoiceAdjustments(slice) : List.of());
        List<ActivityRow> activity = r.empty() ? List.of() : reader.activity(slice);

        // credor → carteira → mês (só o que tem fato)
        Map<UUID, Map<UUID, Map<YearMonth, Sum>>> cells = new TreeMap<>();
        for (CostRow c : costs) {
            if (c.creditorId() != null) {
                cell(cells, c.creditorId(), c.portfolioId(), c.month()).cost(c.category(), c.amount(), c.quantity());
            }
        }
        for (ActivityRow a : activity) {
            if (a.creditorId() != null) {
                cell(cells, a.creditorId(), a.portfolioId(), a.month()).activity(a);
            }
        }

        List<CreditorTrend> creditors = new ArrayList<>();
        for (Map.Entry<UUID, Map<UUID, Map<YearMonth, Sum>>> e : cells.entrySet()) {
            List<PortfolioTrend> portfolios = new ArrayList<>();
            Map<YearMonth, MarginTotals> creditorByMonth = new HashMap<>();
            for (Map.Entry<UUID, Map<YearMonth, Sum>> p : e.getValue().entrySet()) {
                Map<YearMonth, MarginTotals> byMonth = new HashMap<>();
                p.getValue().forEach((month, sum) -> {
                    MarginTotals t = sum.totals();
                    byMonth.put(month, t);
                    creditorByMonth.merge(month, t, MarginTotals::plus);
                });
                portfolios.add(new PortfolioTrend(p.getKey(), points(window, statuses, byMonth)));
            }
            portfolios.sort(Comparator.comparing((PortfolioTrend p) -> p.portfolioId() == null)
                    .thenComparing(p -> p.portfolioId() == null ? "" : p.portfolioId().toString()));
            creditors.add(new CreditorTrend(e.getKey(), points(window, statuses, creditorByMonth), portfolios));
        }
        List<TrendMonth> months = new ArrayList<>();
        for (int i = 0; i < window.size(); i++) {
            months.add(new TrendMonth(window.get(i).toString(), statuses.get(i).name()));
        }
        return new MarginTrend(new Period(r.from(), r.to()), months, computedUntil, creditors);
    }

    private static Sum cell(Map<UUID, Map<UUID, Map<YearMonth, Sum>>> cells, UUID creditorId, UUID portfolioId,
                            YearMonth month) {
        return cells.computeIfAbsent(creditorId, k -> new HashMap<>())
                .computeIfAbsent(portfolioId, k -> new TreeMap<>()).computeIfAbsent(month, k -> new Sum());
    }

    private static List<TrendPoint> points(List<YearMonth> window, List<MonthStatus> statuses,
                                           Map<YearMonth, MarginTotals> byMonth) {
        List<TrendPoint> points = new ArrayList<>(window.size());
        for (int i = 0; i < window.size(); i++) {
            YearMonth month = window.get(i);
            MarginLine line = MarginTrendSeries.point(byMonth.get(month), statuses.get(i));
            points.add(line == null ? new TrendPoint(month.toString(), null, null, null, null)
                    : new TrendPoint(month.toString(), line.recovered(), line.revenue(), line.netMargin(),
                    line.netMarginRate()));
        }
        return points;
    }

    Resolved resolve(MarginFilter filter) {
        MarginFilter f = filter == null ? new MarginFilter(null, null, null, null) : filter;
        LocalDate from = f.from();
        LocalDate to = f.to();
        if (from == null && to == null) {
            YearMonth previous = YearMonth.now(clock.withZone(TimeConfig.BUSINESS_ZONE)).minusMonths(1);
            from = previous.atDay(1);
            to = previous.atEndOfMonth();
        }
        Guard.require(from != null && to != null, "costing.period_required", "Informe o início e o fim do período");
        Guard.require(!from.isAfter(to), "costing.period_invalid", "O início deve ser antes do fim");
        Guard.require(ChronoUnit.DAYS.between(from, to) < properties.maxPeriodDays(), "costing.period_too_long",
                "Período de no máximo " + properties.maxPeriodDays() + " dias");
        Set<UUID> creditors;
        if (accessScope.hasAllCreditors()) {
            creditors = f.creditorIds().isEmpty() ? null : f.creditorIds();
        } else {
            creditors = accessScope.restrictCreditors(f.creditorIds());
        }
        boolean unallocated = creditors == null && f.portfolioIds().isEmpty();
        return new Resolved(from, to, creditors, f.portfolioIds(), unallocated, f.basis());
    }

    MarginReport build(Resolved r) {
        Optional<RefreshState> state = store.state();
        List<CostRow> adjustments = r.empty() ? List.of() : reader.invoiceAdjustments(r.slice());
        List<CostRow> costs = withAdjustments(r, r.empty() ? List.of() : reader.costs(r.slice()), adjustments);
        List<ActivityRow> activity = r.empty() ? List.of() : reader.activity(r.slice());

        // somas por credor × carteira e por mês (a linha sem credor fica à parte)
        Map<UUID, Map<UUID, Sum>> byCreditor = new LinkedHashMap<>();
        Map<YearMonth, Sum> byMonth = new TreeMap<>();
        Sum unallocated = new Sum();
        for (YearMonth m = YearMonth.from(r.from()); !m.isAfter(YearMonth.from(r.to())); m = m.plusMonths(1)) {
            byMonth.put(m, new Sum());
        }
        for (CostRow c : costs) {
            Sum target = c.creditorId() == null ? unallocated
                    : byCreditor.computeIfAbsent(c.creditorId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(c.portfolioId(), k -> new Sum());
            target.cost(c.category(), c.amount(), c.quantity());
            byMonth.computeIfAbsent(c.month(), k -> new Sum()).cost(c.category(), c.amount(), c.quantity());
        }
        for (ActivityRow a : activity) {
            byCreditor.computeIfAbsent(a.creditorId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(a.portfolioId(), k -> new Sum()).activity(a);
            byMonth.computeIfAbsent(a.month(), k -> new Sum()).activity(a);
        }

        Set<UUID> portfolioIds = new HashSet<>();
        byCreditor.values().forEach(m -> m.keySet().stream().filter(Objects::nonNull).forEach(portfolioIds::add));
        Map<UUID, String> creditorNames = reader.creditorNames(byCreditor.keySet());
        Map<UUID, String> portfolioNames = reader.portfolioNames(portfolioIds);

        List<CreditorMargin> creditors = new ArrayList<>();
        MarginTotals total = MarginTotals.ZERO;
        for (Map.Entry<UUID, Map<UUID, Sum>> e : byCreditor.entrySet()) {
            MarginTotals creditorTotal = MarginTotals.ZERO;
            List<PortfolioMargin> portfolios = new ArrayList<>();
            for (Map.Entry<UUID, Sum> p : e.getValue().entrySet()) {
                MarginTotals t = p.getValue().totals();
                creditorTotal = creditorTotal.plus(t);
                portfolios.add(new PortfolioMargin(p.getKey(), p.getKey() == null ? null : portfolioNames.get(p.getKey()),
                        MarginCalculator.line(t)));
            }
            portfolios.sort(Comparator.comparing((PortfolioMargin p) -> p.portfolioId() == null)
                    .thenComparing(p -> p.name() == null ? "" : p.name(), String.CASE_INSENSITIVE_ORDER));
            total = total.plus(creditorTotal);
            creditors.add(new CreditorMargin(e.getKey(), creditorNames.get(e.getKey()), MarginCalculator.line(creditorTotal),
                    portfolios));
        }
        creditors.sort(Comparator.comparing(c -> c.name() == null ? "" : c.name(), String.CASE_INSENSITIVE_ORDER));
        MarginLine unallocatedLine = null;
        if (r.includeUnallocated()) {
            MarginTotals u = unallocated.totals();
            total = total.plus(u);
            unallocatedLine = MarginCalculator.line(u);
        }
        List<MonthMargin> months = byMonth.entrySet().stream()
                .map(e -> new MonthMargin(e.getKey().toString(), MarginCalculator.line(e.getValue().totals()))).toList();
        return new MarginReport(new Period(r.from(), r.to()), r.basis().name(), MarginCalculator.line(total),
                unallocatedLine, creditors, months, warnings(r, state), state.map(RefreshState::lastDay).orElse(null),
                state.map(RefreshState::refreshedAt).orElse(null), reconciliation(r, adjustments));
    }

    // ------------------------------------------------------------------ conciliação com a fatura (§13)

    /** Base conciliada: os custos de canal incluem o ajuste pela fatura (fatos à parte, nunca a estimativa alterada). */
    private static List<CostRow> withAdjustments(Resolved r, List<CostRow> costs, List<CostRow> adjustments) {
        if (r.basis() != MarginBasis.RECONCILED || adjustments.isEmpty()) {
            return costs;
        }
        List<CostRow> all = new ArrayList<>(costs);
        all.addAll(adjustments);
        return all;
    }

    /**
     * Situação de cada mês do período (itens com uso estimado na assessoria × itens com fatura), o ajuste por
     * categoria no recorte visível e, só para a visão da assessoria inteira, as faturas ao lado da estimativa.
     */
    private Reconciliation reconciliation(Resolved r, List<CostRow> adjustments) {
        YearMonth first = YearMonth.from(r.from());
        YearMonth last = YearMonth.from(r.to());
        Map<YearMonth, Map<ChannelItem, ItemEstimate>> estimates = new TreeMap<>();
        reader.channelEstimates(first.atDay(1), last.atEndOfMonth()).forEach(e -> estimates
                .computeIfAbsent(e.month(), k -> new EnumMap<>(ChannelItem.class))
                .merge(e.item(), e.estimate(), ItemEstimate::plus));
        Map<YearMonth, Map<ChannelItem, BigDecimal>> invoiced = new TreeMap<>();
        invoices.findAllByMonthBetweenOrderByMonthDescItemAsc(first.atDay(1), last.atDay(1)).forEach(i -> invoiced
                .computeIfAbsent(i.getMonth(), k -> new EnumMap<>(ChannelItem.class)).put(i.getItem(), i.getAmount()));

        List<MonthReconciliation> months = new ArrayList<>();
        List<InvoiceLine> lines = r.includeUnallocated() ? new ArrayList<>() : null;
        // canais com uso e com fatura são da assessoria inteira: só para quem vê todos os credores (a situação vai a
        // todos — decisão 1307)
        boolean allCreditors = accessScope.hasAllCreditors();
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
            Map<ChannelItem, ItemEstimate> monthEstimates = estimates.getOrDefault(m, Map.of());
            Map<ChannelItem, BigDecimal> monthInvoices = invoiced.getOrDefault(m, Map.of());
            Set<ChannelItem> used = EnumSet.noneOf(ChannelItem.class);
            monthEstimates.forEach((item, e) -> {
                if (e.used()) {
                    used.add(item);
                }
            });
            Set<ChannelItem> withInvoice = monthInvoices.isEmpty() ? EnumSet.noneOf(ChannelItem.class)
                    : EnumSet.copyOf(monthInvoices.keySet());
            Set<ChannelItem> pending = EnumSet.copyOf(used);
            pending.removeAll(withInvoice);
            months.add(new MonthReconciliation(m.toString(), ReconciliationStatus.of(used, withInvoice),
                    allCreditors ? List.copyOf(withInvoice) : List.of(), allCreditors ? List.copyOf(pending) : List.of()));
            if (lines != null) {
                for (Map.Entry<ChannelItem, BigDecimal> i : monthInvoices.entrySet()) {
                    BigDecimal estimated = monthEstimates.getOrDefault(i.getKey(), ItemEstimate.ZERO).amount();
                    lines.add(new InvoiceLine(m.toString(), i.getKey(), money(estimated), i.getValue(),
                            money(i.getValue().subtract(estimated))));
                }
            }
        }
        Map<CostCategory, BigDecimal> byCategory = new EnumMap<>(CostCategory.class);
        for (ChannelItem item : ChannelItem.values()) {
            byCategory.put(item.category(), BigDecimal.ZERO);
        }
        adjustments.forEach(a -> byCategory.merge(a.category(), a.amount(), BigDecimal::add));
        byCategory.replaceAll((c, v) -> money(v));
        return new Reconciliation(ReconciliationStatus.combine(months.stream().map(MonthReconciliation::status)
                .toList()), months, byCategory, lines);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_EVEN);
    }

    private List<MarginWarning> warnings(Resolved r, Optional<RefreshState> state) {
        List<MarginWarning> out = new ArrayList<>();
        if (!r.empty()) {
            for (MarginReader.WarningRow w : reader.warnings(r.slice())) {
                CostWarningCode code = CostWarningCode.valueOf(w.code());
                if (code == CostWarningCode.INVOICE_WITHOUT_USAGE && r.basis() != MarginBasis.RECONCILED) {
                    continue; // o valor sem uso só entra na base conciliada
                }
                BigDecimal count = switch (code) {
                    case HOURS_WITHOUT_BAND -> w.quantity().setScale(1, RoundingMode.HALF_UP);
                    case MISSING_FX_RATE, NO_FX_RATE -> BigDecimal.valueOf(w.months());
                    case MISSING_TARIFF, OVERHEAD_WITHOUT_BASIS, INVOICE_WITHOUT_USAGE ->
                            w.quantity().setScale(0, RoundingMode.HALF_UP);
                };
                if (count.signum() > 0) {
                    out.add(new MarginWarning(code.name(), count));
                }
            }
        }
        // só dias fechados contam: hoje e os dias futuros nunca são apurados (decisão 1256), e o aviso não pode ficar
        // eterno no mês corrente
        LocalDate lastClosed = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)).minusDays(1);
        LocalDate end = r.to().isAfter(lastClosed) ? lastClosed : r.to();
        LocalDate lastDay = state.map(RefreshState::lastDay).orElse(null);
        if (!end.isBefore(r.from()) && (lastDay == null || lastDay.isBefore(end))) {
            LocalDate firstMissing = lastDay == null || lastDay.isBefore(r.from()) ? r.from() : lastDay.plusDays(1);
            out.add(new MarginWarning("NOT_REFRESHED", BigDecimal.valueOf(ChronoUnit.DAYS.between(firstMissing, end) + 1)));
        }
        return out;
    }

    /** Somas mutáveis de um corte. */
    private static final class Sum {
        private final EnumMap<CostCategory, BigDecimal> costs = new EnumMap<>(CostCategory.class);
        private BigDecimal recovered = BigDecimal.ZERO;
        private BigDecimal revenue = BigDecimal.ZERO;
        private long settled;
        private BigDecimal directHours = BigDecimal.ZERO;
        private BigDecimal idleHours = BigDecimal.ZERO;

        void cost(CostCategory category, BigDecimal amount, BigDecimal quantity) {
            costs.merge(category, amount, BigDecimal::add);
            if (category == CostCategory.HUMAN_DIRECT) {
                directHours = directHours.add(quantity);
            } else if (category == CostCategory.HUMAN_IDLE) {
                idleHours = idleHours.add(quantity);
            }
        }

        void activity(ActivityRow a) {
            recovered = recovered.add(a.recovered());
            revenue = revenue.add(a.revenue());
            settled += a.settledAgreements();
        }

        MarginTotals totals() {
            return new MarginTotals(recovered, revenue, costs, settled, directHours, idleHours);
        }
    }
}
