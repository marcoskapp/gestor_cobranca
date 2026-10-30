package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.settlement.application.port.in.SettlementPositionUseCases;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.StatementView;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.TotalsView;
import br.com.gestorcobranca.settlement.application.port.in.TransferUseCases;
import br.com.gestorcobranca.settlement.application.port.in.TransferUseCases.BalanceView;
import br.com.gestorcobranca.settlement.application.port.out.SettlementPositionFingerprints;
import br.com.gestorcobranca.settlement.application.port.out.SettlementSources;
import br.com.gestorcobranca.settlement.application.port.out.SettlementSources.ContractFacts;
import br.com.gestorcobranca.settlement.domain.model.CommissionEntry;
import br.com.gestorcobranca.settlement.domain.model.Receipt;
import br.com.gestorcobranca.settlement.domain.model.ReimbursableExpense;
import br.com.gestorcobranca.settlement.domain.model.SettlementAdjustment;
import br.com.gestorcobranca.settlement.domain.model.SettlementStatement;
import br.com.gestorcobranca.settlement.domain.model.SettlementStatement.Totals;
import br.com.gestorcobranca.settlement.domain.model.StatementStatus;
import br.com.gestorcobranca.settlement.domain.repository.CommissionEntryRepository;
import br.com.gestorcobranca.settlement.domain.repository.ReceiptRepository;
import br.com.gestorcobranca.settlement.domain.repository.SettlementFactRepository;
import br.com.gestorcobranca.settlement.domain.repository.SettlementStatementRepository;
import br.com.gestorcobranca.settlement.domain.service.SettlementCycle;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Posição do repasse em tempo real. Reaproveita {@link SettlementStatementCalculator#simulate} — o mesmo núcleo do
 * cálculo do demonstrativo, sem gravar, vincular nem bloquear —, então o valor mostrado é o que o demonstrativo do
 * período calcularia se fosse fechado agora (testado).
 * <p>
 * <b>Cache curto</b> por (assessoria, credor): 60 s, servido só enquanto a marca de alterações do credor
 * ({@link SettlementPositionFingerprints}: uma consulta agregada e indexada) não mudar. Recebimento, apuração,
 * despesa, demonstrativo, ajuste, repasse ou acordo novos mudam a marca e forçam o recálculo na hora — em qualquer
 * instância, sem depender de evento entre instâncias. O prazo de 60 s cobre o que vem de outros módulos (situação de
 * conciliação no payment) e a virada do dia. Métricas {@code settlement.position.duration{cache}} e
 * {@code settlement.position.requests{cache}}; log técnico DEBUG com o tempo de cálculo e WARN acima de 2 s.
 */
@Service
@Transactional(readOnly = true)
class SettlementPositionService implements SettlementPositionUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(SettlementPositionService.class);
    static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final Duration SLOW = Duration.ofSeconds(2);
    private static final int CACHE_MAX_ENTRIES = 5_000;
    private static final Set<StatementStatus> SENT = Set.of(StatementStatus.SENT, StatementStatus.ACKNOWLEDGED,
            StatementStatus.CONTESTED);

    private final SettlementStatementCalculator calculator;
    private final SettlementStatementRepository statements;
    private final ReceiptRepository receipts;
    private final CommissionEntryRepository entries;
    private final SettlementFactRepository facts;
    private final SettlementSources sources;
    private final SettlementPositionFingerprints fingerprints;
    private final TransferUseCases transfers;
    private final SettlementViews views;
    private final SettlementAccess access;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    private record CacheKey(UUID tenantId, UUID creditorId) {
    }

    private record CacheEntry(String fingerprint, Instant at, PositionView position) {
    }

    private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    SettlementPositionService(SettlementStatementCalculator calculator, SettlementStatementRepository statements,
                              ReceiptRepository receipts, CommissionEntryRepository entries,
                              SettlementFactRepository facts, SettlementSources sources,
                              SettlementPositionFingerprints fingerprints, TransferUseCases transfers,
                              SettlementViews views, SettlementAccess access, AccessScope accessScope,
                              CurrentActor currentActor, MeterRegistry meterRegistry, Clock clock) {
        this.calculator = calculator;
        this.statements = statements;
        this.receipts = receipts;
        this.entries = entries;
        this.facts = facts;
        this.sources = sources;
        this.fingerprints = fingerprints;
        this.transfers = transfers;
        this.views = views;
        this.access = access;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Override
    public PositionView position(UUID creditorId) {
        access.requireAny(Permission.SETTLEMENT_VIEW);
        accessScope.requireVisible("creditor", creditorId, creditorId);
        Instant started = Instant.now(clock);
        CacheKey key = new CacheKey(currentActor.tenantId(), creditorId);
        String fingerprint = fingerprints.of(creditorId);
        CacheEntry hit = cache.get(key);
        if (hit != null && hit.fingerprint().equals(fingerprint) && hit.at().plus(CACHE_TTL).isAfter(started)
                && hit.position().asOf().equals(today())) {
            record(started, true);
            PositionView p = hit.position();
            return new PositionView(p.creditorId(), p.periodStart(), p.asOf(), p.expectedPeriodEnd(), p.settlementDay(),
                    p.openStatementId(), p.openStatementStatus(), p.totals(), p.direction(), p.daily(), p.previous(),
                    p.previousDaily(), p.closedWithBalance(), p.outstandingToTransfer(), p.outstandingToReceive(),
                    p.calculatedAt(), true, p.calculationMillis());
        }
        PositionView computed = compute(creditorId, started);
        if (cache.size() >= CACHE_MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(key, new CacheEntry(fingerprint, started, computed));
        Duration took = record(started, false);
        LOG.atLevel(took.compareTo(SLOW) > 0 ? org.slf4j.event.Level.WARN : org.slf4j.event.Level.DEBUG)
                .addKeyValue("creditorId", creditorId).addKeyValue("durationMs", took.toMillis())
                .addKeyValue("receipts", computed.totals().receipts())
                .log("Posição de repasse calculada (simulação do demonstrativo)");
        return computed;
    }

    @Override
    public List<StatementView> sentToCreditor(UUID creditorId) {
        access.requireAny(Permission.SETTLEMENT_VIEW);
        accessScope.requireVisible("creditor", creditorId, creditorId);
        return statements.findByCreditorIdOrderByPeriodStartDesc(creditorId).stream()
                .filter(s -> SENT.contains(s.getStatus())).map(views::statement).toList();
    }

    private PositionView compute(UUID creditorId, Instant now) {
        LocalDate today = today();
        List<SettlementStatement> all = statements.findByCreditorIdOrderByPeriodStartDesc(creditorId).stream()
                .filter(s -> s.getStatus() != StatementStatus.CANCELLED).toList();
        Optional<SettlementStatement> open = all.stream().filter(s -> s.getStatus().isOpen()).findFirst();
        Optional<SettlementStatement> lastClosed = all.stream().filter(s -> s.getStatus().isClosed())
                .max(Comparator.comparing(SettlementStatement::getPeriodEnd));
        int day = sources.contracts(creditorId).stream().filter(ct -> ct.effectiveOn(today))
                .map(ContractFacts::settlementDay).findFirst().orElse(1);
        LocalDate start = open.map(SettlementStatement::getPeriodStart)
                .or(() -> lastClosed.map(s -> s.getPeriodEnd().plusDays(1)))
                .orElseGet(() -> SettlementCycle.containing(today, day).start());
        if (start.isAfter(today)) {
            start = today;
        }
        LocalDate expectedEnd = SettlementCycle.containing(start, day).end();

        SettlementStatementCalculator.Computation c = calculator.simulate(creditorId, start, today,
                open.map(SettlementStatement::getId).orElse(null), now, today);
        Totals t = c.totals();
        TotalsView totals = new TotalsView(t.receivedAgency().amount(), t.receivedCreditor().amount(),
                t.reversed().amount(), t.recovered().amount(), t.reconciled().amount(), t.agencyHonorarium().amount(),
                t.creditorHonorarium().amount(), t.commission().amount(), t.fixedFee().amount(), t.expenses().amount(),
                t.adjustments().amount(), t.netTransfer().amount(), Money.ZERO.amount(), Money.ZERO.amount(),
                t.negotiated().amount(), t.forecastCommission().amount(), t.pendingReconciliation().amount(),
                t.pendingRules().amount(), t.openDivergences(), t.receipts());
        List<DailyPoint> daily = daily(start, today, c.includedReceipts(), c.entries(), c.includedExpenses(),
                t.adjustments());

        PreviousPeriod previous = null;
        List<DailyPoint> previousDaily = List.of();
        if (lastClosed.isPresent()) {
            SettlementStatement p = lastClosed.get();
            List<Receipt> prevReceipts = receipts.findByStatementId(p.getId());
            Map<UUID, CommissionEntry> prevEntries = entries.findByReceiptIdIn(prevReceipts.stream()
                    .map(Receipt::getId).toList()).stream()
                    .collect(Collectors.toMap(CommissionEntry::getReceiptId, Function.identity(), (a, b) -> a));
            Money prevAdjustments = statements.findAdjustments(p.getId()).stream()
                    .filter(a -> a.getStatus() == SettlementAdjustment.Status.APPLIED)
                    .map(SettlementAdjustment::getAmount).reduce(Money.ZERO, Money::plus);
            previousDaily = daily(p.getPeriodStart(), p.getPeriodEnd(), prevReceipts, prevEntries,
                    facts.findExpensesByStatement(p.getId()), prevAdjustments);
            previous = new PreviousPeriod(p.getId(), p.getSequenceNumber(), p.getPeriodStart(), p.getPeriodEnd(),
                    p.getStatus().name(), p.totals().recovered().amount(), p.totals().netTransfer().amount());
        }

        List<BalanceView> balances = transfers.balances(creditorId);
        BigDecimal toTransfer = balances.stream().map(BalanceView::toTransfer).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal toReceive = balances.stream().map(BalanceView::toReceive).reduce(BigDecimal.ZERO, BigDecimal::add);
        String direction = t.netTransfer().isPositive() ? "AGENCY_TO_CREDITOR"
                : t.netTransfer().isZero() ? "NONE" : "CREDITOR_TO_AGENCY";
        return new PositionView(creditorId, start, today, expectedEnd, day, open.map(SettlementStatement::getId)
                .orElse(null), open.map(s -> s.getStatus().name()).orElse(null), totals, direction, daily, previous,
                previousDaily, balances.stream().flatMap(b -> b.statements().stream()).toList(),
                toTransfer.setScale(2), toReceive.setScale(2), now, false,
                Duration.between(now, Instant.now(clock)).toMillis());
    }

    /**
     * Acumulado por dia do período: recuperado (recebimentos e estornos) e líquido (efeito no repasse − despesas;
     * ajustes no último dia). Itens com competência anterior ao início contam no dia 1.
     */
    static List<DailyPoint> daily(LocalDate start, LocalDate end, List<Receipt> included,
                                  Map<UUID, CommissionEntry> entryByReceipt, List<ReimbursableExpense> expenses,
                                  Money adjustments) {
        int days = (int) ChronoUnit.DAYS.between(start, end) + 1;
        if (days < 1) {
            return List.of();
        }
        TreeMap<Integer, Money[]> byDay = new TreeMap<>();
        for (Receipt r : included) {
            int d = dayOf(start, r.getCompetenceDate(), days);
            Money[] v = byDay.computeIfAbsent(d, k -> new Money[]{Money.ZERO, Money.ZERO});
            v[0] = v[0].plus(r.getAmount());
            CommissionEntry e = entryByReceipt.get(r.getId());
            if (e != null) {
                v[1] = v[1].plus(e.getTransferEffect());
            }
        }
        for (ReimbursableExpense e : expenses) {
            Money[] v = byDay.computeIfAbsent(dayOf(start, e.getCompetenceDate(), days),
                    k -> new Money[]{Money.ZERO, Money.ZERO});
            v[1] = v[1].minus(e.deduction());
        }
        Money[] last = byDay.computeIfAbsent(days, k -> new Money[]{Money.ZERO, Money.ZERO});
        last[1] = last[1].plus(adjustments);
        List<DailyPoint> points = new ArrayList<>(days);
        Money recovered = Money.ZERO;
        Money net = Money.ZERO;
        for (int d = 1; d <= days; d++) {
            Money[] v = byDay.get(d);
            if (v != null) {
                recovered = recovered.plus(v[0]);
                net = net.plus(v[1]);
            }
            points.add(new DailyPoint(d, start.plusDays(d - 1L), recovered.amount(), net.amount()));
        }
        return points;
    }

    private static int dayOf(LocalDate start, LocalDate date, int days) {
        long d = ChronoUnit.DAYS.between(start, date) + 1;
        return (int) Math.max(1, Math.min(days, d));
    }

    private Duration record(Instant started, boolean cached) {
        Duration took = Duration.between(started, Instant.now(clock));
        String tag = cached ? "hit" : "miss";
        Timer.builder("settlement.position.duration").tag("cache", tag).register(meterRegistry).record(took);
        Counter.builder("settlement.position.requests").tag("cache", tag).register(meterRegistry).increment();
        return took;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }
}
