package br.com.gestorcobranca.costing.application.service;

import br.com.gestorcobranca.costing.application.CostingProperties;
import br.com.gestorcobranca.costing.application.port.in.MarginQueries;
import br.com.gestorcobranca.costing.application.port.out.CostFactStore;
import br.com.gestorcobranca.costing.application.port.out.CostFactStore.RefreshState;
import br.com.gestorcobranca.costing.application.port.out.MarginReader;
import br.com.gestorcobranca.costing.application.port.out.MarginReader.ActivityRow;
import br.com.gestorcobranca.costing.application.port.out.MarginReader.CostRow;
import br.com.gestorcobranca.costing.application.port.out.MarginReader.Slice;
import br.com.gestorcobranca.costing.domain.model.CostCategory;
import br.com.gestorcobranca.costing.domain.model.CostWarningCode;
import br.com.gestorcobranca.costing.domain.model.MarginLine;
import br.com.gestorcobranca.costing.domain.model.MarginTotals;
import br.com.gestorcobranca.costing.domain.service.MarginCalculator;
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
 * credor, carteira e mês e aplica as fórmulas do domínio ({@link MarginCalculator}).
 */
@Service
@Transactional(readOnly = true)
class MarginQueryService implements MarginQueries {

    static final String BASIS = "ESTIMATED";

    private final MarginReader reader;
    private final CostFactStore store;
    private final AccessScope accessScope;
    private final CostingMetrics metrics;
    private final CostingProperties properties;
    private final Clock clock;

    MarginQueryService(MarginReader reader, CostFactStore store, AccessScope accessScope, CostingMetrics metrics,
                       CostingProperties properties, Clock clock) {
        this.reader = reader;
        this.store = store;
        this.accessScope = accessScope;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /** Recorte resolvido: período e escopo. {@code creditorIds} nulo = todos; vazio = nenhum (fora do escopo). */
    record Resolved(LocalDate from, LocalDate to, Set<UUID> creditorIds, Set<UUID> portfolioIds,
                    boolean includeUnallocated) {

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
        return new Resolved(from, to, creditors, f.portfolioIds(), unallocated);
    }

    MarginReport build(Resolved r) {
        Optional<RefreshState> state = store.state();
        List<CostRow> costs = r.empty() ? List.of() : reader.costs(r.slice());
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
        return new MarginReport(new Period(r.from(), r.to()), BASIS, MarginCalculator.line(total), unallocatedLine,
                creditors, months, warnings(r, state), state.map(RefreshState::lastDay).orElse(null),
                state.map(RefreshState::refreshedAt).orElse(null));
    }

    private List<MarginWarning> warnings(Resolved r, Optional<RefreshState> state) {
        List<MarginWarning> out = new ArrayList<>();
        if (!r.empty()) {
            for (MarginReader.WarningRow w : reader.warnings(r.slice())) {
                CostWarningCode code = CostWarningCode.valueOf(w.code());
                BigDecimal count = switch (code) {
                    case HOURS_WITHOUT_BAND -> w.quantity().setScale(1, RoundingMode.HALF_UP);
                    case MISSING_FX_RATE, NO_FX_RATE -> BigDecimal.valueOf(w.months());
                    case MISSING_TARIFF, OVERHEAD_WITHOUT_BASIS -> w.quantity().setScale(0, RoundingMode.HALF_UP);
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
