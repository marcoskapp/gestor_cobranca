package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries.TeamStructure;
import br.com.gestorcobranca.incentive.application.IncentiveProperties;
import br.com.gestorcobranca.incentive.application.port.in.PeriodUseCases;
import br.com.gestorcobranca.incentive.application.service.StatementCalculator.Outcome;
import br.com.gestorcobranca.incentive.application.service.StatementCalculator.PeriodContext;
import br.com.gestorcobranca.incentive.domain.model.attribution.BeneficiaryType;
import br.com.gestorcobranca.incentive.domain.model.ledger.CommissionLedgerEntry;
import br.com.gestorcobranca.incentive.domain.model.ledger.CommissionPeriod;
import br.com.gestorcobranca.incentive.domain.model.statement.CommissionStatement;
import br.com.gestorcobranca.incentive.domain.repository.LedgerRepository;
import br.com.gestorcobranca.incentive.domain.repository.StatementRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fechamento do período (§5.5): libera lançamentos vencidos, aplica o plano vigente e gera um
 * extrato {@code CALCULATED} por destinatário. Executado pelo job diário (quando a carência do
 * último dia terminou) ou manualmente; o período fica fechado e não reabre.
 */
@Service
@Transactional
class PeriodClosingService implements PeriodUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(PeriodClosingService.class);

    private final LedgerRepository ledger;
    private final StatementRepository statements;
    private final ClearingService clearing;
    private final PeriodCalendar periods;
    private final StatementCalculator calculator;
    private final PlanResolver planResolver;
    private final HolidayCalendar calendar;
    private final Workforce workforce;
    private final IncentiveAccess access;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final IncentiveMetrics metrics;
    private final IncentiveProperties properties;
    private final Clock clock;

    PeriodClosingService(LedgerRepository ledger, StatementRepository statements, ClearingService clearing,
                         PeriodCalendar periods, StatementCalculator calculator, PlanResolver planResolver,
                         HolidayCalendar calendar, Workforce workforce, IncentiveAccess access, AuditTrail auditTrail,
                         BusinessEventLogger businessEvents, IncentiveMetrics metrics, IncentiveProperties properties,
                         Clock clock) {
        this.ledger = ledger;
        this.statements = statements;
        this.clearing = clearing;
        this.periods = periods;
        this.calculator = calculator;
        this.planResolver = planResolver;
        this.calendar = calendar;
        this.workforce = workforce;
        this.access = access;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PeriodView> list() {
        access.requirePermission(Permission.COMMISSION_STATEMENT_MANAGE);
        return ledger.findPeriods().stream().map(this::view).toList();
    }

    @Override
    public PeriodView close(YearMonth month) {
        access.requirePermission(Permission.COMMISSION_STATEMENT_MANAGE);
        LocalDate today = today();
        Guard.require(month != null && month.isBefore(YearMonth.from(today)), "commission_period.not_finished",
                "Só é possível fechar período já encerrado");
        LocalDate closeable = closeableFrom(month);
        Guard.requireState(!today.isBefore(closeable), "commission_period.grace_pending",
                "A carência do último dia do período termina em " + closeable);
        return view(close(month, CommissionPeriod.CloseTrigger.MANUAL, access.actor().id()));
    }

    /** Fecha automaticamente os períodos abertos cuja carência terminou. @return períodos fechados */
    int closeDuePeriods() {
        LocalDate today = today();
        clearing.clearDue(today);
        int closed = 0;
        List<CommissionPeriod> open = ledger.findPeriods().stream().filter(p -> !p.isClosed())
                .filter(p -> p.month().isBefore(YearMonth.from(today))).sorted((a, b) -> a.getPeriodStart()
                        .compareTo(b.getPeriodStart())).toList();
        for (CommissionPeriod period : open) {
            if (today.isBefore(closeableFrom(period.month()))) {
                break;
            }
            close(period.month(), CommissionPeriod.CloseTrigger.SCHEDULED, access.actor().id());
            closed++;
        }
        return closed;
    }

    private CommissionPeriod close(YearMonth month, CommissionPeriod.CloseTrigger trigger, UUID actorId) {
        Instant started = Instant.now(clock);
        periods.ensure(month);
        CommissionPeriod period = ledger.findPeriodForUpdate(month.atDay(1)).orElseThrow();
        if (period.isClosed()) {
            throw new ConflictException("commission_period.already_closed", "Período já fechado");
        }
        Optional<CommissionPeriod> previousOpen = ledger.findPeriods().stream()
                .filter(p -> !p.isClosed() && p.getPeriodStart().isBefore(period.getPeriodStart())).findFirst();
        Guard.requireState(previousOpen.isEmpty(), "commission_period.previous_open",
                "Feche antes o período anterior em aberto");
        clearing.clearDue(today());

        List<CommissionLedgerEntry> entries = ledger.findCleared(period.getPeriodStart());
        List<TeamStructure> teams = workforce.teams();
        PeriodContext seed = new PeriodContext(period.getPeriodStart(), period.getPeriodEnd(), entries, teams, Map.of());
        Set<UUID> recipients = calculator.recipients(seed);
        PeriodContext ctx = new PeriodContext(period.getPeriodStart(), period.getPeriodEnd(), entries, teams,
                workforce.members(StatementCalculator.involvedUsers(recipients, teams)));

        Instant now = Instant.now(clock);
        Money total = Money.ZERO;
        Money credited = Money.ZERO;
        int count = 0;
        List<UUID> statementIds = new ArrayList<>();
        for (UUID userId : recipients.stream().sorted().toList()) {
            if (statements.findByPeriodAndBeneficiary(period.getPeriodStart(), userId).isPresent()) {
                continue;
            }
            Outcome outcome = calculator.calculate(userId, ctx, Optional.empty());
            boolean ownEntries = entries.stream().anyMatch(e -> e.getBeneficiaryType() == BeneficiaryType.USER
                    && e.getBeneficiaryId().equals(userId));
            boolean poolShare = outcome.result().lines().stream()
                    .anyMatch(l -> l.component() == br.com.gestorcobranca.incentive.domain.service.CalculationResult.Component.POOL);
            if (!ownEntries && !poolShare) {
                // membro de equipe com crédito, mas sem participação no pool pelo seu plano: sem extrato
                continue;
            }
            CommissionStatement statement = statements.save(new CommissionStatement(period.getId(), period.getPeriodStart(),
                    userId, outcome.calculation(), actorId, now));
            for (CommissionLedgerEntry entry : entries) {
                if (entry.getBeneficiaryType() == BeneficiaryType.USER && entry.getBeneficiaryId().equals(userId)) {
                    entry.includeIn(statement.getId());
                    ledger.save(entry);
                }
            }
            total = total.plus(statement.getPayableAmount());
            credited = credited.plus(statement.getCreditedRecovery());
            statementIds.add(statement.getId());
            count++;
        }
        period.close(actorId, trigger, count, now);
        ledger.savePeriod(period);
        auditTrail.record(AuditEntry.of("commission_period.closed", "commission_period", period.getId())
                .change("status", "OPEN", "CLOSED")
                .meta("period", month.toString()).meta("trigger", trigger).meta("statements", count));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.COMMISSION_PERIOD_CLOSED)
                .reference("commission_period", period.getId()).amount(CommissionBands.aggregate(total, count))
                .attr("totalBand", CommissionBands.of(total))
                .attr("period", month.toString()).attr("trigger", trigger).attr("statements", count)
                .attr("creditedRecovery", credited.amount())
                .attr("costPerRecovered", credited.isPositive() && count >= CommissionBands.MIN_AGGREGATE
                        ? total.amount().divide(credited.amount(), 6, java.math.RoundingMode.HALF_EVEN) : null));
        metrics.periodClosed(trigger.name(), count, Duration.between(started, Instant.now(clock)));
        LOG.atInfo().addKeyValue("period", month.toString()).addKeyValue("statements", count)
                .addKeyValue("trigger", trigger.name()).log("Período de comissão fechado");
        return period;
    }

    /** Primeiro dia em que o período pode ser fechado: último dia + maior carência vigente (dias úteis). */
    LocalDate closeableFrom(YearMonth month) {
        int grace = planResolver.maxGraceBusinessDays(month.atEndOfMonth(), properties.defaultGraceBusinessDays());
        return calendar.plusBusinessDays(month.atEndOfMonth(), grace);
    }

    private PeriodView view(CommissionPeriod p) {
        return new PeriodView(p.getId(), p.month(), p.getPeriodStart(), p.getPeriodEnd(), p.getStatus().name(),
                p.getClosedAt(), p.getClosedBy(), p.getCloseTrigger() == null ? null : p.getCloseTrigger().name(),
                p.getStatementsCount(), p.isClosed() ? null : closeableFrom(p.month()));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }
}
