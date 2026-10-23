package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries.WorkforceMember;
import br.com.gestorcobranca.incentive.application.IncentiveProperties;
import br.com.gestorcobranca.incentive.application.port.in.StatementUseCases;
import br.com.gestorcobranca.incentive.application.service.StatementCalculator.Outcome;
import br.com.gestorcobranca.incentive.application.service.StatementCalculator.PeriodContext;
import br.com.gestorcobranca.incentive.domain.model.attribution.BeneficiaryType;
import br.com.gestorcobranca.incentive.domain.model.ledger.CommissionLedgerEntry;
import br.com.gestorcobranca.incentive.domain.model.ledger.CommissionPeriod;
import br.com.gestorcobranca.incentive.domain.model.statement.CommissionStatement;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementAdjustment;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementDispute;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementLine;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementStatus;
import br.com.gestorcobranca.incentive.domain.repository.LedgerRepository;
import br.com.gestorcobranca.incentive.domain.repository.StatementRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@Transactional
class StatementService implements StatementUseCases {

    private final StatementRepository statements;
    private final LedgerRepository ledger;
    private final StatementCalculator calculator;
    private final Workforce workforce;
    private final IncentiveAccess access;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final IncentiveMetrics metrics;
    private final IncentiveProperties properties;
    private final Clock clock;

    StatementService(StatementRepository statements, LedgerRepository ledger, StatementCalculator calculator,
                     Workforce workforce, IncentiveAccess access, AccessScope accessScope, AuditTrail auditTrail,
                     BusinessEventLogger businessEvents, IncentiveMetrics metrics, IncentiveProperties properties,
                     Clock clock) {
        this.statements = statements;
        this.ledger = ledger;
        this.calculator = calculator;
        this.workforce = workforce;
        this.access = access;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ consultas

    @Override
    @Transactional(readOnly = true)
    public PageResponse<StatementSummary> list(YearMonth period, UUID userId, String status, PageQuery page) {
        Set<UUID> visible = access.visibleUsers();
        Set<UUID> filter;
        if (userId != null) {
            access.requireVisible(userId, "commission_statement");
            filter = Set.of(userId);
        } else {
            filter = visible;
        }
        StatementStatus statusFilter = status == null || status.isBlank() ? null : enumOf(StatementStatus.class, status,
                "statement.status_invalid");
        Page<CommissionStatement> result = statements.search(period == null ? null : period.atDay(1), filter, statusFilter,
                page.toPageable(Sort.by(Sort.Order.desc("periodStart"), Sort.Order.asc("beneficiaryId"))));
        Map<UUID, WorkforceMember> names = workforce.members(result.getContent().stream()
                .map(CommissionStatement::getBeneficiaryId).toList());
        boolean others = result.getContent().stream().anyMatch(s -> !s.getBeneficiaryId().equals(access.actor().id()));
        if (others) {
            access.logCompensationListing("commission_statement",
                    userId != null ? "user:" + userId : (visible == null ? "all" : "team"));
        }
        return PageResponse.of(result, s -> summary(s, names.get(s.getBeneficiaryId())));
    }

    @Override
    @Transactional(readOnly = true)
    public StatementDetail get(UUID statementId) {
        CommissionStatement statement = visible(statementId);
        access.logCompensationView(statement.getBeneficiaryId(), "commission_statement", statementId);
        return detail(statement);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<LedgerEntryView> ledger(UUID userId, YearMonth period, PageQuery page) {
        UUID target = userId == null ? access.actor().id() : userId;
        access.requireVisible(target, "commission_ledger");
        access.logCompensationView(target, "commission_ledger", target);
        Set<UUID> creditors = accessScope.hasAllCreditors() ? Set.of() : accessScope.restrictCreditors(Set.of());
        if (!accessScope.hasAllCreditors() && creditors.isEmpty() && !target.equals(access.actor().id())) {
            return new PageResponse<>(List.of(), page.page(), page.size(), 0, 0);
        }
        Page<CommissionLedgerEntry> entries = ledger.findByBeneficiary(BeneficiaryType.USER, target,
                period == null ? null : period.atDay(1), target.equals(access.actor().id()) ? Set.of() : creditors,
                page.toPageable());
        return PageResponse.of(entries, StatementService::entryView);
    }

    @Override
    @Transactional(readOnly = true)
    public StatementPreview preview(UUID userId) {
        UUID target = userId == null ? access.actor().id() : userId;
        access.requireVisible(target, "commission_preview");
        access.logCompensationView(target, "commission_preview", target);
        YearMonth month = YearMonth.from(today());
        LocalDate start = month.atDay(1);
        List<CommissionLedgerEntry> cleared = ledger.findCleared(start, BeneficiaryType.USER, target);
        List<CommissionLedgerEntry> pending = ledger.findPending(BeneficiaryType.USER, target);
        List<CommissionLedgerEntry> projected = Stream.concat(cleared.stream(), pending.stream()).toList();
        Map<UUID, WorkforceMember> members = workforce.members(List.of(target));
        var teams = workforce.teams();
        Outcome clearedOutcome = calculator.calculate(target, new PeriodContext(start, month.atEndOfMonth(), cleared, teams,
                members), Optional.empty());
        Outcome projectedOutcome = calculator.calculate(target, new PeriodContext(start, month.atEndOfMonth(), projected,
                teams, members), Optional.empty());
        Money pendingRecovery = pending.stream().map(CommissionLedgerEntry::getCreditedAmount).reduce(Money.ZERO, Money::plus);
        CommissionStatement.PlanRef plan = projectedOutcome.calculation().plan();
        return new StatementPreview(target, month, plan.code(), plan.version(),
                projectedOutcome.calculation().outcome().name(), clearedOutcome.calculation().creditedRecovery().amount(),
                pendingRecovery.amount(), clearedOutcome.calculation().calculated().amount(),
                projectedOutcome.calculation().calculated().amount(),
                projectedOutcome.calculation().lines().stream().map(StatementService::lineView).toList());
    }

    // ------------------------------------------------------------------ comandos

    @Override
    public StatementDetail recalculate(UUID statementId) {
        access.requirePermission(Permission.COMMISSION_STATEMENT_MANAGE);
        CommissionStatement statement = load(statementId);
        Guard.requireState(!statement.isFinal(), "statement.final", "Extrato aprovado é imutável");
        CommissionPeriod period = ledger.findPeriodById(statement.getPeriodId()).orElseThrow();
        List<CommissionLedgerEntry> entries = ledger.findCleared(period.getPeriodStart());
        var teams = workforce.teams();
        PeriodContext ctx = new PeriodContext(period.getPeriodStart(), period.getPeriodEnd(), entries, teams,
                workforce.members(StatementCalculator.involvedUsers(Set.of(statement.getBeneficiaryId()), teams)));
        Outcome outcome = calculator.calculate(statement.getBeneficiaryId(), ctx, Optional.empty());
        Money before = statement.getCalculatedAmount();
        String outcomeBefore = statement.getOutcome().name();
        statement.recalculate(outcome.calculation(), access.actor().id(), now());
        statements.save(statement);
        auditTrail.record(AuditEntry.of("commission_statement.recalculated", "commission_statement", statementId)
                .change("calculatedAmount", before, statement.getCalculatedAmount())
                .change("outcome", outcomeBefore, statement.getOutcome().name())
                .meta("beneficiaryId", statement.getBeneficiaryId()));
        return detail(statement);
    }

    @Override
    public DisputeView openDispute(UUID statementId, DisputeCommand command) {
        CommissionStatement statement = visible(statementId);
        Actor actor = access.actor();
        if (!actor.id().equals(statement.getBeneficiaryId())) {
            throw new ScopeAccessDeniedException();
        }
        Guard.require(command.lineNumber() == null || statement.getLines().stream()
                .anyMatch(l -> l.getLineNumber() == command.lineNumber()), "dispute.line_not_found", "Linha inexistente no extrato");
        if (command.ledgerEntryId() != null) {
            CommissionLedgerEntry entry = ledger.findById(command.ledgerEntryId())
                    .filter(e -> e.getBeneficiaryId().equals(statement.getBeneficiaryId()))
                    .orElseThrow(() -> ResourceNotFoundException.of("commission_entry", command.ledgerEntryId()));
            Guard.require(entry.getBeneficiaryType() == BeneficiaryType.USER, "dispute.entry_invalid", "Lançamento inválido");
        }
        statement.openDispute();
        StatementDispute dispute = statements.saveDispute(new StatementDispute(statementId, command.lineNumber(),
                command.ledgerEntryId(), command.agreementId(), command.reason(), actor.id(), now()));
        statements.save(statement);
        auditTrail.record(AuditEntry.of("commission_statement.disputed", "commission_statement", statementId)
                .created("disputeId", dispute.getId()).created("lineNumber", command.lineNumber())
                .created("ledgerEntryId", command.ledgerEntryId()).created("agreementId", command.agreementId())
                .reason(dispute.getReason()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.COMMISSION_STATEMENT_DISPUTED)
                .reference("commission_statement", statementId)
                .attr("disputeId", dispute.getId()).attr("period", YearMonth.from(statement.getPeriodStart()).toString())
                .attr("target", command.lineNumber() != null ? "LINE" : command.ledgerEntryId() != null ? "ENTRY" : "AGREEMENT")
                .attr("component", command.lineNumber() == null ? null : statement.getLines().stream()
                        .filter(l -> l.getLineNumber() == command.lineNumber()).map(StatementLine::getComponent)
                        .findFirst().orElse(null)));
        metrics.statementTransition(statement.getStatus().name());
        return disputeView(dispute);
    }

    @Override
    public DisputeView resolveDispute(UUID statementId, UUID disputeId, boolean accepted, String resolution,
                                      BigDecimal adjustmentAmount) {
        access.requirePermission(Permission.COMMISSION_STATEMENT_MANAGE);
        CommissionStatement statement = load(statementId);
        StatementDispute dispute = statements.findDispute(disputeId).filter(d -> d.getStatementId().equals(statementId))
                .orElseThrow(() -> ResourceNotFoundException.of("statement_dispute", disputeId));
        Guard.require(accepted || adjustmentAmount == null, "dispute.adjustment_requires_acceptance",
                "Ajuste só acompanha contestação aceita");
        UUID adjustmentId = null;
        if (accepted && adjustmentAmount != null && adjustmentAmount.signum() != 0) {
            adjustmentId = createAdjustment(statement, dispute.getId(), Money.of(adjustmentAmount),
                    "Contestação aceita: " + resolution).getId();
        }
        dispute.resolve(accepted, resolution, adjustmentId, access.actor().id(), now());
        statements.saveDispute(dispute);
        reconcile(statement);
        auditTrail.record(AuditEntry.of("commission_statement.dispute_resolved", "commission_statement", statementId)
                .change("disputeStatus", "OPEN", dispute.getStatus().name()).meta("disputeId", disputeId)
                .meta("adjustmentId", adjustmentId).reason(dispute.getResolution()));
        return disputeView(dispute);
    }

    @Override
    public AdjustmentView adjust(UUID statementId, BigDecimal amount, String reason) {
        access.requirePermission(Permission.COMMISSION_STATEMENT_MANAGE);
        CommissionStatement statement = load(statementId);
        Guard.require(amount != null, "adjustment.amount_invalid", "Informe o valor do ajuste");
        StatementAdjustment adjustment = createAdjustment(statement, null, Money.of(amount), reason);
        reconcile(statement);
        return adjustmentView(adjustment);
    }

    @Override
    public AdjustmentView decideAdjustment(UUID statementId, UUID adjustmentId, boolean approve, String note) {
        access.requirePermission(Permission.COMMISSION_STATEMENT_MANAGE);
        CommissionStatement statement = load(statementId);
        Guard.requireState(!statement.isFinal(), "statement.final", "Extrato aprovado é imutável");
        StatementAdjustment adjustment = statements.findAdjustment(adjustmentId)
                .filter(a -> a.getStatementId().equals(statementId))
                .orElseThrow(() -> ResourceNotFoundException.of("statement_adjustment", adjustmentId));
        Guard.require(!access.actor().id().equals(statement.getBeneficiaryId()), "adjustment.self_decision",
                "Não é permitido decidir ajuste do próprio extrato");
        adjustment.decide(approve, note, access.actor().id(), now());
        statements.saveAdjustment(adjustment);
        reconcile(statement);
        auditTrail.record(AuditEntry.of("commission_statement.adjustment_decided", "commission_statement", statementId)
                .change("adjustmentStatus", "PENDING_SECOND_APPROVAL", adjustment.getStatus().name())
                .meta("adjustmentId", adjustmentId).reason(note));
        if (adjustment.isApplied()) {
            logAdjusted(statement, adjustment);
        }
        return adjustmentView(adjustment);
    }

    @Override
    public StatementDetail approve(UUID statementId) {
        access.requirePermission(Permission.COMMISSION_STATEMENT_APPROVE);
        CommissionStatement statement = load(statementId);
        CommissionPeriod period = ledger.findPeriodById(statement.getPeriodId()).orElseThrow();
        List<StatementDispute> disputes = statements.findDisputes(statementId);
        List<StatementAdjustment> adjustments = statements.findAdjustments(statementId);
        Set<UUID> adjusters = new HashSet<>();
        adjustments.forEach(a -> {
            adjusters.add(a.getRequestedBy());
            if (a.getDecidedBy() != null) {
                adjusters.add(a.getDecidedBy());
            }
        });
        disputes.stream().filter(d -> d.getResolvedBy() != null).forEach(d -> adjusters.add(d.getResolvedBy()));
        String before = statement.getStatus().name();
        statement.approve(access.actor().id(), period.getClosedBy(), adjusters,
                (int) disputes.stream().filter(StatementDispute::isOpen).count(),
                (int) adjustments.stream().filter(StatementAdjustment::isPending).count(), now());
        statements.save(statement);
        auditTrail.record(AuditEntry.of("commission_statement.approved", "commission_statement", statementId)
                .change("status", before, statement.getStatus().name())
                .meta("beneficiaryId", statement.getBeneficiaryId())
                .meta("period", YearMonth.from(statement.getPeriodStart()).toString()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.COMMISSION_STATEMENT_APPROVED)
                .reference("commission_statement", statementId)
                .attr("period", YearMonth.from(statement.getPeriodStart()).toString())
                .attr("outcome", statement.getOutcome()).attr("planCode", statement.getPlanCode())
                .attr("planVersion", statement.getPlanVersion())
                .attr("payableBand", CommissionBands.of(statement.getPayableAmount()))
                .attr("adjusted", !statement.getAdjustmentsAmount().isZero()));
        metrics.statementTransition(statement.getStatus().name());
        return detail(statement);
    }

    // ------------------------------------------------------------------ auxiliares

    private StatementAdjustment createAdjustment(CommissionStatement statement, UUID disputeId, Money amount, String reason) {
        Guard.requireState(!statement.isFinal(), "statement.final", "Extrato aprovado é imutável; ajuste no período seguinte");
        Guard.require(!access.actor().id().equals(statement.getBeneficiaryId()), "adjustment.self_adjustment",
                "Não é permitido ajustar o próprio extrato");
        StatementAdjustment adjustment = statements.saveAdjustment(new StatementAdjustment(statement.getId(), disputeId,
                amount, reason, Money.of(properties.dualApprovalThreshold()), access.actor().id(), now()));
        auditTrail.record(AuditEntry.of("commission_statement.adjustment_requested", "commission_statement",
                        statement.getId())
                .created("adjustmentId", adjustment.getId()).created("amount", amount)
                .created("status", adjustment.getStatus().name()).reason(adjustment.getReason()));
        if (adjustment.isApplied()) {
            logAdjusted(statement, adjustment);
        }
        return adjustment;
    }

    private void logAdjusted(CommissionStatement statement, StatementAdjustment adjustment) {
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.COMMISSION_STATEMENT_ADJUSTED)
                .reference("commission_statement", statement.getId())
                .attr("adjustmentId", adjustment.getId()).attr("fromDispute", adjustment.getDisputeId() != null)
                .attr("direction", adjustment.getAmount().isNegative() ? "DEBIT" : "CREDIT")
                .attr("amountBand", CommissionBands.of(adjustment.getAmount().abs()))
                .attr("dualApproval", adjustment.getDecidedBy() != null)
                .attr("period", YearMonth.from(statement.getPeriodStart()).toString()));
    }

    private void reconcile(CommissionStatement statement) {
        List<StatementAdjustment> adjustments = statements.findAdjustments(statement.getId());
        Money applied = adjustments.stream().filter(StatementAdjustment::isApplied).map(StatementAdjustment::getAmount)
                .reduce(Money.ZERO, Money::plus);
        int open = (int) statements.findDisputes(statement.getId()).stream().filter(StatementDispute::isOpen).count();
        String before = statement.getStatus().name();
        statement.reconcile(open, applied, adjustments.stream().anyMatch(StatementAdjustment::isApplied));
        statements.save(statement);
        if (!before.equals(statement.getStatus().name())) {
            metrics.statementTransition(statement.getStatus().name());
        }
    }

    private CommissionStatement load(UUID statementId) {
        return statements.findById(statementId)
                .orElseThrow(() -> ResourceNotFoundException.of("commission_statement", statementId));
    }

    /** Extrato visível ao ator (fora do escopo → 404, sem revelar existência). */
    private CommissionStatement visible(UUID statementId) {
        CommissionStatement statement = load(statementId);
        Set<UUID> visible = access.visibleUsers();
        if (visible != null && !visible.contains(statement.getBeneficiaryId())) {
            throw ResourceNotFoundException.of("commission_statement", statementId);
        }
        return statement;
    }

    private StatementDetail detail(CommissionStatement s) {
        WorkforceMember member = workforce.member(s.getBeneficiaryId()).orElse(null);
        return new StatementDetail(summary(s, member), s.getLines().stream().map(StatementService::lineView).toList(),
                s.getCalculation(), statements.findDisputes(s.getId()).stream().map(StatementService::disputeView).toList(),
                statements.findAdjustments(s.getId()).stream().map(StatementService::adjustmentView).toList(),
                s.getApprovedBy());
    }

    private static StatementSummary summary(CommissionStatement s, WorkforceMember member) {
        return new StatementSummary(s.getId(), YearMonth.from(s.getPeriodStart()), s.getBeneficiaryId(),
                member == null ? null : member.name(), s.getStatus().name(), s.getOutcome().name(), s.getPlanCode(),
                s.getPlanVersion(), s.getCalculatedAmount().amount(), s.getAdjustmentsAmount().amount(),
                s.getPayableAmount().amount(), s.getWithheldAmount().amount(), s.getCreditedRecovery().amount(),
                s.getApprovedAt());
    }

    static StatementLineView lineView(StatementLine l) {
        return new StatementLineView(l.getLineNumber(), l.getComponent(), l.getDescription(), l.getSegmentKey(),
                l.getBaseAmount() == null ? null : l.getBaseAmount().amount(), l.getRate(), l.getFactor(),
                l.getAmount().amount());
    }

    static LedgerEntryView entryView(CommissionLedgerEntry e) {
        return new LedgerEntryView(e.getId(), e.getKind().name(), e.getStatus().name(),
                e.getRole() == null ? null : e.getRole().name(), e.getAgreementId(), e.getPaymentId(), e.getCreditorId(),
                e.getPortfolioId(), e.getDelayBucket().name(), e.getOrigin().name(), e.getChannel(),
                e.getBaseAmount().amount(), e.getCreditWeight().value(), e.getRenegotiationFactor().value(),
                e.getCreditedAmount().amount(), e.getCreditNote(), e.getPaidOn(), e.getClearsOn(),
                e.getPeriodStart() == null ? null : YearMonth.from(e.getPeriodStart()), e.getStatementId());
    }

    private static DisputeView disputeView(StatementDispute d) {
        return new DisputeView(d.getId(), d.getLineNumber(), d.getLedgerEntryId(), d.getAgreementId(), d.getReason(),
                d.getStatus().name(), d.getOpenedBy(), d.getOpenedAt(), d.getResolution(), d.getResolvedBy(),
                d.getResolvedAt(), d.getAdjustmentId());
    }

    private static AdjustmentView adjustmentView(StatementAdjustment a) {
        return new AdjustmentView(a.getId(), a.getDisputeId(), a.getAmount().amount(), a.getReason(), a.getStatus().name(),
                a.getRequestedBy(), a.getRequestedAt(), a.getDecidedBy(), a.getDecidedAt(), a.getDecisionNote());
    }

    static <E extends Enum<E>> E enumOf(Class<E> type, String value, String code) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new br.com.gestorcobranca.shared.domain.BusinessRuleException(code, "Valor inválido: " + value);
        }
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }
}
