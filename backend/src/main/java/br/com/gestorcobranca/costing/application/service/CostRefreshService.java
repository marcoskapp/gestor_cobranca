package br.com.gestorcobranca.costing.application.service;

import br.com.gestorcobranca.costing.application.CostingProperties;
import br.com.gestorcobranca.costing.application.port.in.CostRefreshUseCases;
import br.com.gestorcobranca.costing.application.port.out.CostFactStore;
import br.com.gestorcobranca.costing.application.port.out.UsageReader;
import br.com.gestorcobranca.costing.domain.model.CostCategory;
import br.com.gestorcobranca.costing.domain.model.CostKey;
import br.com.gestorcobranca.costing.domain.model.CostWarningCode;
import br.com.gestorcobranca.costing.domain.model.Overhead;
import br.com.gestorcobranca.costing.domain.model.OverheadAllocation;
import br.com.gestorcobranca.costing.domain.repository.ChannelTariffRepository;
import br.com.gestorcobranca.costing.domain.repository.CostBandRepository;
import br.com.gestorcobranca.costing.domain.repository.FxRateRepository;
import br.com.gestorcobranca.costing.domain.repository.OverheadRepository;
import br.com.gestorcobranca.costing.domain.repository.UserCostBandRepository;
import br.com.gestorcobranca.costing.domain.service.CostAccumulator;
import br.com.gestorcobranca.costing.domain.service.HumanCost;
import br.com.gestorcobranca.costing.domain.service.OverheadAllocator;
import br.com.gestorcobranca.costing.domain.service.RateBook;
import br.com.gestorcobranca.costing.domain.service.UsageCost;
import br.com.gestorcobranca.costing.domain.service.WhatsAppConversations;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Apuração dos custos da assessoria corrente (desenho §4): lê o uso do período pelas views de contrato, aplica as
 * regras do domínio com os cadastros vigentes e substitui os fatos do período numa única transação (leitores veem a
 * versão anterior até o commit). Os custos fixos são rateados por <b>mês inteiro</b> — todo mês que toca o período —,
 * depois de gravados os fatos que servem de base (horas, recuperado, casos).
 * <p>
 * Concorrência: o job agendado espera a apuração em andamento da assessoria; as rotas HTTP (reapuração manual e o
 * rateio refeito ao criar ou excluir custo fixo) só tentam o lock e, se ocupado, falham na hora com
 * {@code costing.refresh_running} (409), sem prender a requisição nem a conexão.
 * <p>
 * Log técnico só com período, linhas e duração: nenhum valor de custo nem de pessoa.
 */
@Service
@Transactional
class CostRefreshService implements CostRefreshUseCases {

    /** Versão das regras de apuração gravada no estado (sobe quando uma regra muda e o histórico pede reapuração). */
    static final int RULES_VERSION = 1;

    private static final Logger LOG = LoggerFactory.getLogger(CostRefreshService.class);

    private static final Set<CostCategory> USAGE_CATEGORIES = EnumSet.complementOf(EnumSet.of(CostCategory.OVERHEAD));
    private static final Set<CostWarningCode> USAGE_WARNINGS =
            EnumSet.complementOf(EnumSet.of(CostWarningCode.OVERHEAD_WITHOUT_BASIS));

    private final UsageReader usage;
    private final CostFactStore store;
    private final CostBandRepository bands;
    private final UserCostBandRepository userBands;
    private final ChannelTariffRepository tariffs;
    private final FxRateRepository fxRates;
    private final OverheadRepository overheads;
    private final CurrentActor currentActor;
    private final AccessScope accessScope;
    private final CostingMetrics metrics;
    private final CostingProperties properties;
    private final AuditTrail audit;
    private final Clock clock;

    CostRefreshService(UsageReader usage, CostFactStore store, CostBandRepository bands,
                       UserCostBandRepository userBands, ChannelTariffRepository tariffs, FxRateRepository fxRates,
                       OverheadRepository overheads, CurrentActor currentActor, AccessScope accessScope,
                       CostingMetrics metrics, CostingProperties properties, AuditTrail audit, Clock clock) {
        this.usage = usage;
        this.store = store;
        this.bands = bands;
        this.userBands = userBands;
        this.tariffs = tariffs;
        this.fxRates = fxRates;
        this.overheads = overheads;
        this.currentActor = currentActor;
        this.accessScope = accessScope;
        this.metrics = metrics;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public RefreshResult refresh(LocalDate from, LocalDate to) {
        if (!currentActor.get().has(Permission.COST_RATE_MANAGE)) {
            accessScope.requirePermission(Permission.COST_MANAGE);
        }
        accessScope.requireAllCreditors("costing_refresh");
        LocalDate yesterday = today().minusDays(1);
        Guard.require(from != null && to != null, "costing.refresh_period_required", "Informe o período");
        Guard.require(!from.isAfter(to), "costing.refresh_period_invalid", "O início deve ser antes do fim");
        // o dia de hoje ainda não fechou: apurá-lo marcaria como apurado um dia que ainda recebe uso
        Guard.require(!to.isAfter(yesterday), "costing.refresh_future",
                "A reapuração vai até ontem: o dia de hoje ainda não fechou");
        Guard.require(ChronoUnit.DAYS.between(from, to) < properties.maxRefreshDays(), "costing.refresh_too_long",
                "Reapure no máximo " + properties.maxRefreshDays() + " dias por vez");
        requireLock();
        RefreshResult result = run(from, to, "manual");
        audit.record(AuditEntry.of("costing.refreshed", "cost_refresh", currentActor.get().tenantId())
                .meta("from", from).meta("to", to).meta("rows", result.rows()));
        return result;
    }

    /** Apuração agendada: os últimos {@code refreshDays} dias até ontem (chamada pelo job, na transação da assessoria). */
    RefreshResult refreshScheduled() {
        LocalDate to = today().minusDays(1);
        store.lockForRefresh(properties.refreshStatementTimeout());
        return run(to.minusDays(properties.refreshDays() - 1L), to, "scheduled");
    }

    /** Refaz só o rateio dos custos fixos do mês (ao criar ou excluir um custo fixo). */
    void reallocateOverheads(YearMonth month) {
        requireLock();
        allocateOverheads(month);
    }

    /** Modo das rotas HTTP: não espera a apuração em andamento (ver a nota de concorrência da classe). */
    private void requireLock() {
        Guard.requireState(store.tryLockForRefresh(properties.refreshStatementTimeout()), "costing.refresh_running",
                "A apuração dos custos está em andamento: tente de novo em alguns minutos");
    }

    /** Apura o período; o lock da apuração já foi obtido pelo chamador (esperando ou só tentando). */
    private RefreshResult run(LocalDate from, LocalDate to, String trigger) {
        long start = System.nanoTime();
        try {
            RateBook rates = new RateBook(bands.findAllByOrderByNameAscValidFromAsc(),
                    userBands.findAllByOrderByUserIdAscValidFromAsc(), tariffs.findAllByOrderByChannelAscValidFromAsc(),
                    fxRates.findAllByOrderByMonthAsc());
            CostAccumulator acc = new CostAccumulator();
            HumanCost.apply(usage.directTime(from, to), usage.loggedTime(from, to), rates, acc);
            usage.calls(from, to).forEach(c -> UsageCost.telephony(c, acc));
            usage.ai(from, to).forEach(a -> UsageCost.ai(a, rates, acc));
            usage.messages(from, to).forEach(m -> UsageCost.messages(m, rates, acc));
            whatsApp(from, to, rates, acc);
            usage.variableCommission(from, to).forEach(p -> UsageCost.posted(p, CostCategory.VARIABLE_COMMISSION, acc));
            usage.legalExpenses(from, to).forEach(p -> UsageCost.posted(p, CostCategory.LEGAL, acc));

            long rows = store.replaceFacts(from, to, USAGE_CATEGORIES, acc.facts());
            store.replaceWarnings(from, to, USAGE_WARNINGS, acc.warnings());
            store.replaceActivity(from, to);
            for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
                rows += allocateOverheads(m);
            }
            Instant now = Instant.now(clock);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
            store.saveState(from, to, now, elapsed.toMillis(), rows, RULES_VERSION);
            metrics.refreshed(trigger, "ok", elapsed, rows);
            LOG.atInfo().addKeyValue("process", "costing.refresh").addKeyValue("trigger", trigger)
                    .addKeyValue("from", from).addKeyValue("to", to).addKeyValue("rows", rows)
                    .addKeyValue("durationMs", elapsed.toMillis()).log("Custos da assessoria apurados");
            return new RefreshResult(from, to, rows, now);
        } catch (RuntimeException e) {
            metrics.refreshed(trigger, "error", Duration.ofNanos(System.nanoTime() - start), 0);
            throw e;
        }
    }

    /**
     * Janelas de 24 h do WhatsApp: lê também os dois dias anteriores ao período para saber se o primeiro envio do
     * período cai numa janela aberta antes dele; só as janelas abertas dentro do período são cobradas.
     */
    private void whatsApp(LocalDate from, LocalDate to, RateBook rates, CostAccumulator acc) {
        WhatsAppConversations windows = new WhatsAppConversations();
        UUID[] current = new UUID[1];
        usage.templateSends(from.minusDays(2), to, send -> {
            if (current[0] != null && !current[0].equals(send.conversationId())) {
                windows.forget(current[0]);
            }
            current[0] = send.conversationId();
            windows.accept(send).ifPresent(channel -> {
                if (!send.day().isBefore(from)) {
                    UsageCost.whatsAppConversation(send.day(), send.creditorId(), send.portfolioId(), channel, rates,
                            acc);
                }
            });
        });
    }

    private long allocateOverheads(YearMonth month) {
        CostAccumulator acc = new CostAccumulator();
        Map<OverheadAllocation, Map<CostKey, BigDecimal>> basis = new EnumMap<>(OverheadAllocation.class);
        for (Overhead o : overheads.findAllByMonth(month.atDay(1))) {
            OverheadAllocator.allocate(o, basis.computeIfAbsent(o.getAllocation(),
                    a -> store.overheadBasis(month, a)), acc);
        }
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        store.replaceWarnings(first, last, Set.of(CostWarningCode.OVERHEAD_WITHOUT_BASIS), acc.warnings());
        return store.replaceFacts(first, last, Set.of(CostCategory.OVERHEAD), acc.facts());
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }
}
