package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.settlement.application.SettlementProperties;
import br.com.gestorcobranca.settlement.application.port.in.CommissionLedgerQueries.ReceiptView;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases;
import br.com.gestorcobranca.settlement.application.port.out.SettlementLocks;
import br.com.gestorcobranca.settlement.application.port.out.SettlementSources;
import br.com.gestorcobranca.settlement.application.port.out.SettlementSources.ContractFacts;
import br.com.gestorcobranca.settlement.domain.model.ReimbursableExpense;
import br.com.gestorcobranca.settlement.domain.model.SettlementStatement;
import br.com.gestorcobranca.settlement.domain.model.SettlementAdjustment;
import br.com.gestorcobranca.settlement.domain.model.SettlementExport;
import br.com.gestorcobranca.settlement.domain.model.StatementStatus;
import br.com.gestorcobranca.settlement.domain.repository.ReceiptRepository;
import br.com.gestorcobranca.settlement.domain.repository.SettlementFactRepository;
import br.com.gestorcobranca.settlement.domain.repository.SettlementStatementRepository;
import br.com.gestorcobranca.settlement.domain.service.SettlementCycle;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.settlement.SettlementClosed;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class SettlementStatementService implements SettlementStatementUseCases {

    static final String RESOURCE = "settlement_statement";

    private final SettlementStatementRepository statements;
    private final ReceiptRepository receipts;
    private final SettlementFactRepository facts;
    private final SettlementStatementCalculator calculator;
    private final ReceiptPages pages;
    private final SettlementSources sources;
    private final SettlementLocks locks;
    private final SettlementViews views;
    private final SettlementAccess access;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final ApplicationEventPublisher events;
    private final SettlementMetrics metrics;
    private final SettlementProperties properties;
    private final Clock clock;

    SettlementStatementService(SettlementStatementRepository statements, ReceiptRepository receipts, SettlementFactRepository facts,
                     SettlementStatementCalculator calculator, ReceiptPages pages, SettlementSources sources,
                     SettlementLocks locks, SettlementViews views, SettlementAccess access, AccessScope accessScope,
                     CurrentActor currentActor, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                     ApplicationEventPublisher events, SettlementMetrics metrics, SettlementProperties properties,
                     Clock clock) {
        this.statements = statements;
        this.receipts = receipts;
        this.facts = facts;
        this.calculator = calculator;
        this.pages = pages;
        this.sources = sources;
        this.locks = locks;
        this.views = views;
        this.access = access;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.events = events;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public StatementView create(CreateStatementCommand c) {
        accessScope.requirePermission(Permission.SETTLEMENT_CLOSE);
        Guard.require(c.creditorId() != null, "statement.creditor_required", "Credor obrigatório");
        accessScope.requireCreditor(c.creditorId());
        locks.lockCreditor(c.creditorId());
        List<SettlementStatement> existing = statements.findByCreditorIdOrderByPeriodStartDesc(c.creditorId());
        if (existing.stream().anyMatch(s -> s.getStatus().isOpen())) {
            throw new ConflictException("statement.open_exists",
                    "Já há demonstrativo em aberto para o credor: conclua ou cancele antes de abrir outro");
        }
        LocalDate start = c.periodStart();
        LocalDate end = c.periodEnd();
        if (start == null || end == null) {
            PeriodSuggestion suggestion = suggestion(c.creditorId(), existing);
            start = start == null ? suggestion.periodStart() : start;
            end = end == null ? suggestion.periodEnd() : end;
        }
        Optional<SettlementStatement> last = existing.stream().filter(s -> s.getStatus() != StatementStatus.CANCELLED)
                .max(Comparator.comparing(SettlementStatement::getPeriodEnd));
        if (last.isPresent()) {
            LocalDate expected = last.get().getPeriodEnd().plusDays(1);
            Guard.require(start.equals(expected), "statement.period_not_contiguous",
                    "O período deve começar em " + expected + ", no dia seguinte ao fim do demonstrativo anterior");
        }
        int sequence = existing.stream().mapToInt(SettlementStatement::getSequenceNumber).max().orElse(0) + 1;
        SettlementStatement s = statements.save(new SettlementStatement(c.creditorId(), sequence, start, end));
        auditTrail.record(AuditEntry.of("settlement_statement.created", RESOURCE, s.getId()).creditor(s.getCreditorId())
                .created("sequenceNumber", sequence).created("periodStart", start).created("periodEnd", end));
        metrics.statementTransition(s.getStatus().name());
        return views.statement(s);
    }

    @Override
    @Transactional(readOnly = true)
    public PeriodSuggestion suggestPeriod(UUID creditorId) {
        access.requireAny(Permission.SETTLEMENT_VIEW, Permission.SETTLEMENT_CLOSE);
        accessScope.requireVisible("creditor", creditorId, creditorId);
        return suggestion(creditorId, statements.findByCreditorIdOrderByPeriodStartDesc(creditorId));
    }

    private PeriodSuggestion suggestion(UUID creditorId, List<SettlementStatement> existing) {
        LocalDate today = today();
        int day = sources.contracts(creditorId).stream().filter(ct -> ct.effectiveOn(today))
                .map(ContractFacts::settlementDay).findFirst().orElse(1);
        Optional<SettlementStatement> last = existing.stream().filter(s -> s.getStatus() != StatementStatus.CANCELLED)
                .max(Comparator.comparing(SettlementStatement::getPeriodEnd));
        SettlementCycle.Period period = last.map(s -> {
            LocalDate start = s.getPeriodEnd().plusDays(1);
            return new SettlementCycle.Period(start, SettlementCycle.containing(start, day).end());
        }).orElseGet(() -> SettlementCycle.lastClosed(today, day));
        return new PeriodSuggestion(creditorId, period.start(), period.end(), day);
    }

    @Override
    @Transactional(readOnly = true)
    public StatementView get(UUID statementId) {
        access.requireAny(Permission.SETTLEMENT_VIEW, Permission.SETTLEMENT_CLOSE, Permission.SETTLEMENT_APPROVE);
        return views.statement(loadVisible(statementId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<StatementView> search(StatementFilter f, PageQuery page) {
        access.requireAny(Permission.SETTLEMENT_VIEW, Permission.SETTLEMENT_CLOSE, Permission.SETTLEMENT_APPROVE);
        if (f.creditorId() != null) {
            accessScope.requireCreditor(f.creditorId());
        }
        Set<UUID> allowed = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
        StatementStatus status = f.status() == null ? null
                : SettlementAccess.parse(StatementStatus.class, f.status(), "statement.status_invalid");
        return PageResponse.of(statements.search(allowed, f.creditorId(), status,
                page.toPageable(Sort.by(Sort.Direction.DESC, "periodStart").and(Sort.by("id")))), views::statement);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ReceiptView> receipts(UUID statementId, PageQuery page) {
        access.requireAny(Permission.SETTLEMENT_VIEW, Permission.SETTLEMENT_CLOSE, Permission.SETTLEMENT_APPROVE);
        SettlementStatement s = loadVisible(statementId);
        return pages.of(receipts.findByStatementId(s.getId(),
                page.toPageable(Sort.by("competenceDate").and(Sort.by("id")))));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpenseView> expenses(UUID statementId) {
        access.requireAny(Permission.SETTLEMENT_VIEW, Permission.SETTLEMENT_CLOSE, Permission.SETTLEMENT_APPROVE);
        SettlementStatement s = loadVisible(statementId);
        return facts.findExpensesByStatement(s.getId()).stream().map(SettlementViews::expense).toList();
    }

    @Override
    public StatementView calculate(UUID statementId) {
        accessScope.requirePermission(Permission.SETTLEMENT_CLOSE);
        SettlementStatement s = loadForCommand(statementId);
        locks.lockCreditor(s.getCreditorId());
        Guard.requireState(s.getStatus().isOpen(), "statement.not_open", "Demonstrativo não está em aberto");
        Instant started = Instant.now(clock);
        LocalDate today = today();
        Guard.require(s.getPeriodEnd().isBefore(today), "statement.period_not_ended",
                "O período só pode ser calculado depois de encerrado");
        SettlementStatementCalculator.Result result = calculator.calculate(s, started, today);
        String previous = s.getStatus().name();
        s.calculated(result.totals(), result.calculationJson(), currentActor.actorId(), started, today);
        statements.save(s);
        SettlementStatement.Totals t = s.totals();
        auditTrail.record(AuditEntry.of("settlement_statement.calculated", RESOURCE, s.getId()).creditor(s.getCreditorId())
                .change("status", previous, s.getStatus()).created("receipts", t.receipts())
                .created("recovered", t.recovered().amount()).created("commission", t.commission().amount())
                .created("netTransfer", t.netTransfer().amount()));
        businessEvents.log(statementEvent(BusinessEventTypes.SETTLEMENT_CALCULATED, s).amount(t.netTransfer())
                .attr("recovered", t.recovered().amount()).attr("commission", t.commission().amount())
                .attr("pendingReconciliation", t.pendingReconciliation().amount())
                .attr("pendingRules", t.pendingRules().amount()).attr("openDivergences", t.openDivergences())
                .attr("receipts", t.receipts()));
        metrics.calculated(Duration.between(started, Instant.now(clock)), result.included());
        metrics.statementTransition(s.getStatus().name());
        return views.statement(s);
    }

    @Override
    public StatementView review(UUID statementId) {
        accessScope.requirePermission(Permission.SETTLEMENT_CLOSE);
        SettlementStatement s = loadForCommand(statementId);
        s.review(currentActor.actorId(), Instant.now(clock));
        statements.save(s);
        auditTrail.record(AuditEntry.of("settlement_statement.reviewed", RESOURCE, s.getId()).creditor(s.getCreditorId())
                .change("status", StatementStatus.CALCULATED, StatementStatus.REVIEWED));
        metrics.statementTransition(s.getStatus().name());
        return views.statement(s);
    }

    @Override
    public StatementView approve(UUID statementId) {
        accessScope.requirePermission(Permission.SETTLEMENT_APPROVE);
        SettlementStatement s = loadForCommand(statementId);
        List<SettlementAdjustment> adjustments = statements.findAdjustments(s.getId());
        if (adjustments.stream().anyMatch(a -> a.getStatus() == SettlementAdjustment.Status.PENDING_SECOND_APPROVAL)) {
            throw new ConflictException("statement.adjustment_pending",
                    "Há ajuste aguardando segunda aprovação: decida-o antes de aprovar o demonstrativo");
        }
        Set<UUID> requesters = new HashSet<>();
        adjustments.stream().filter(a -> a.getStatus() == SettlementAdjustment.Status.APPLIED)
                .forEach(a -> requesters.add(a.getRequestedBy()));
        Instant now = Instant.now(clock);
        s.approve(currentActor.actorId(), now, requesters);
        statements.save(s);
        SettlementStatement.Totals t = s.totals();
        List<UUID> legalExpenses = facts.findExpensesByStatement(s.getId()).stream()
                .filter(e -> e.getKind() == ReimbursableExpense.Kind.CHARGE
                        && e.getEffect() == ReimbursableExpense.Effect.DEDUCT)
                .map(ReimbursableExpense::getLegalExpenseId).toList();
        auditTrail.record(AuditEntry.of("settlement_statement.approved", RESOURCE, s.getId()).creditor(s.getCreditorId())
                .change("status", StatementStatus.REVIEWED, StatementStatus.APPROVED)
                .meta("calculatedBy", s.getCalculatedBy()).meta("reviewedBy", s.getReviewedBy())
                .created("netTransfer", t.netTransfer().amount()));
        BigDecimal effectiveRate = t.recovered().isPositive()
                ? t.commission().plus(t.fixedFee()).amount().multiply(BigDecimal.valueOf(100))
                .divide(t.recovered().amount(), 4, RoundingMode.HALF_EVEN) : BigDecimal.ZERO;
        businessEvents.log(statementEvent(BusinessEventTypes.SETTLEMENT_CLOSED, s).amount(t.netTransfer())
                .attr("recovered", t.recovered().amount()).attr("reversed", t.reversed().amount())
                .attr("commission", t.commission().amount()).attr("fixedFees", t.fixedFee().amount())
                .attr("agencyHonorarium", t.agencyHonorarium().amount()).attr("expenses", t.expenses().amount())
                .attr("adjustments", t.adjustments().amount()).attr("receipts", t.receipts())
                .attr("effectiveRate", effectiveRate).attr("negotiated", t.negotiated().amount())
                .attr("forecastCommission", t.forecastCommission().amount())
                .attr("pendingReconciliation", t.pendingReconciliation().amount())
                .attr("openDivergences", t.openDivergences())
                .attr("daysAfterPeriod", ChronoUnit.DAYS.between(s.getPeriodEnd(), today())));
        events.publishEvent(new SettlementClosed(Ids.newId(), currentActor.tenantId(), now, s.getId(), s.getCreditorId(),
                s.getPeriodStart(), s.getPeriodEnd(), t.recovered().amount(),
                t.commission().plus(t.fixedFee()).amount(), t.agencyHonorarium().amount(), t.expenses().amount(),
                t.netTransfer().amount(), legalExpenses));
        metrics.statementTransition(s.getStatus().name());
        return views.statement(s);
    }

    @Override
    public StatementView send(UUID statementId, SendCommand c) {
        accessScope.requirePermission(Permission.SETTLEMENT_CLOSE);
        SettlementStatement s = loadForCommand(statementId);
        Guard.require(c.exportIds() != null && !c.exportIds().isEmpty(), "statement.exports_required",
                "Informe os arquivos enviados ao credor");
        for (UUID exportId : c.exportIds()) {
            SettlementExport export = statements.findExport(exportId)
                    .orElseThrow(() -> new ResourceNotFoundException("statement_export", exportId));
            Guard.require(export.getStatementId().equals(s.getId()), "statement.export_mismatch",
                    "Arquivo de outro demonstrativo");
        }
        s.send(currentActor.actorId(), c.channel() == null ? null : c.channel().trim().toUpperCase(), c.reference(),
                Instant.now(clock));
        statements.save(s);
        auditTrail.record(AuditEntry.of("settlement_statement.sent", RESOURCE, s.getId()).creditor(s.getCreditorId())
                .change("status", StatementStatus.APPROVED, StatementStatus.SENT).created("channel", s.getSentChannel())
                .created("exports", c.exportIds().toString()));
        businessEvents.log(statementEvent(BusinessEventTypes.SETTLEMENT_SENT, s).attr("channel", s.getSentChannel())
                .attr("exports", c.exportIds().size())
                .attr("hoursAfterApproval", Duration.between(s.getApprovedAt(), s.getSentAt()).toHours()));
        metrics.statementTransition(s.getStatus().name());
        return views.statement(s);
    }

    @Override
    public StatementView acknowledge(UUID statementId, String note) {
        accessScope.requirePermission(Permission.SETTLEMENT_CLOSE);
        SettlementStatement s = loadForCommand(statementId);
        StatementStatus previous = s.getStatus();
        s.acknowledge(currentActor.actorId(), note, Instant.now(clock));
        statements.save(s);
        auditTrail.record(AuditEntry.of("settlement_statement.acknowledged", RESOURCE, s.getId())
                .creditor(s.getCreditorId()).change("status", previous, s.getStatus()));
        businessEvents.log(statementEvent(BusinessEventTypes.SETTLEMENT_ACKNOWLEDGED, s)
                .attr("afterContestation", previous == StatementStatus.CONTESTED)
                .attr("daysAfterSent", ChronoUnit.DAYS.between(s.getSentAt(), s.getCreditorResponseAt())));
        metrics.statementTransition(s.getStatus().name());
        return views.statement(s);
    }

    @Override
    public StatementView contest(UUID statementId, String reason) {
        accessScope.requirePermission(Permission.SETTLEMENT_CLOSE);
        SettlementStatement s = loadForCommand(statementId);
        s.contest(currentActor.actorId(), reason, Instant.now(clock));
        statements.save(s);
        auditTrail.record(AuditEntry.of("settlement_statement.contested", RESOURCE, s.getId()).creditor(s.getCreditorId())
                .change("status", StatementStatus.SENT, StatementStatus.CONTESTED).reason(s.getCreditorResponseNote()));
        businessEvents.log(statementEvent(BusinessEventTypes.SETTLEMENT_CONTESTED, s).amount(s.getNetTransferAmount())
                .attr("daysAfterSent", ChronoUnit.DAYS.between(s.getSentAt(), s.getCreditorResponseAt())));
        metrics.statementTransition(s.getStatus().name());
        return views.statement(s);
    }

    @Override
    public StatementView cancel(UUID statementId, String reason) {
        accessScope.requirePermission(Permission.SETTLEMENT_CLOSE);
        SettlementStatement s = loadForCommand(statementId);
        locks.lockCreditor(s.getCreditorId());
        StatementStatus previous = s.getStatus();
        s.cancel(reason);
        calculator.release(s);
        statements.save(s);
        auditTrail.record(AuditEntry.of("settlement_statement.cancelled", RESOURCE, s.getId()).creditor(s.getCreditorId())
                .change("status", previous, s.getStatus()).reason(s.getCancelledReason()));
        metrics.statementTransition(s.getStatus().name());
        return views.statement(s);
    }

    @Override
    public AdjustmentView requestAdjustment(UUID statementId, AdjustmentCommand c) {
        accessScope.requirePermission(Permission.SETTLEMENT_CLOSE);
        SettlementStatement s = loadForCommand(statementId);
        Guard.requireState(s.getStatus() == StatementStatus.CALCULATED || s.getStatus() == StatementStatus.REVIEWED,
                "statement.not_calculated", "Ajustes só em demonstrativo calculado (antes da aprovação)");
        SettlementAdjustment.Category category = SettlementAccess.parse(SettlementAdjustment.Category.class, c.category(),
                "adjustment.category_invalid");
        Guard.require(c.amount() != null && c.amount().scale() <= 2, "adjustment.amount_invalid",
                "Valor do ajuste com até 2 casas decimais");
        if (c.relatedStatementId() != null) {
            SettlementStatement related = loadVisible(c.relatedStatementId());
            Guard.require(related.getCreditorId().equals(s.getCreditorId()) && related.getStatus().isClosed()
                            && !related.getId().equals(s.getId()), "adjustment.related_statement_invalid",
                    "O demonstrativo corrigido deve ser um aprovado anterior do mesmo credor");
        }
        Money amount = Money.of(c.amount());
        boolean dual = amount.abs().isGreaterThan(Money.of(properties.adjustmentDualApprovalThreshold()));
        SettlementAdjustment adjustment = statements.save(new SettlementAdjustment(s.getId(), category, amount, c.reason(),
                c.relatedStatementId(), dual, currentActor.actorId(), Instant.now(clock)));
        auditTrail.record(AuditEntry.of("settlement_adjustment.requested", "settlement_adjustment", adjustment.getId())
                .creditor(s.getCreditorId()).created("statementId", s.getId()).created("category", category)
                .created("amount", amount.amount()).created("status", adjustment.getStatus())
                .reason(adjustment.getReason()));
        metrics.adjustment(adjustment.getStatus().name());
        if (adjustment.getStatus() == SettlementAdjustment.Status.APPLIED) {
            applied(s, adjustment);
        }
        return SettlementViews.adjustment(adjustment);
    }

    @Override
    public AdjustmentView approveAdjustment(UUID adjustmentId, String note) {
        accessScope.requirePermission(Permission.SETTLEMENT_APPROVE);
        SettlementAdjustment adjustment = loadAdjustment(adjustmentId);
        SettlementStatement s = loadForCommand(adjustment.getStatementId());
        Guard.requireState(s.getStatus() == StatementStatus.CALCULATED || s.getStatus() == StatementStatus.REVIEWED,
                "statement.not_calculated", "O demonstrativo não aceita mais ajustes");
        adjustment.approve(currentActor.actorId(), note, Instant.now(clock));
        statements.save(adjustment);
        auditTrail.record(AuditEntry.of("settlement_adjustment.approved", "settlement_adjustment", adjustment.getId())
                .creditor(s.getCreditorId()).change("status", SettlementAdjustment.Status.PENDING_SECOND_APPROVAL,
                        SettlementAdjustment.Status.APPLIED).meta("requestedBy", adjustment.getRequestedBy()));
        metrics.adjustment(adjustment.getStatus().name());
        applied(s, adjustment);
        return SettlementViews.adjustment(adjustment);
    }

    @Override
    public AdjustmentView rejectAdjustment(UUID adjustmentId, String note) {
        accessScope.requirePermission(Permission.SETTLEMENT_APPROVE);
        SettlementAdjustment adjustment = loadAdjustment(adjustmentId);
        SettlementStatement s = loadForCommand(adjustment.getStatementId());
        adjustment.reject(currentActor.actorId(), note, Instant.now(clock));
        statements.save(adjustment);
        auditTrail.record(AuditEntry.of("settlement_adjustment.rejected", "settlement_adjustment", adjustment.getId())
                .creditor(s.getCreditorId()).change("status", SettlementAdjustment.Status.PENDING_SECOND_APPROVAL,
                        SettlementAdjustment.Status.REJECTED).reason(adjustment.getDecisionNote()));
        metrics.adjustment(adjustment.getStatus().name());
        return SettlementViews.adjustment(adjustment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdjustmentView> adjustments(UUID statementId) {
        access.requireAny(Permission.SETTLEMENT_VIEW, Permission.SETTLEMENT_CLOSE, Permission.SETTLEMENT_APPROVE);
        SettlementStatement s = loadVisible(statementId);
        return statements.findAdjustments(s.getId()).stream().map(SettlementViews::adjustment).toList();
    }

    private void applied(SettlementStatement s, SettlementAdjustment adjustment) {
        Money total = statements.findAdjustments(s.getId()).stream()
                .filter(a -> a.getStatus() == SettlementAdjustment.Status.APPLIED)
                .map(SettlementAdjustment::getAmount).reduce(Money.ZERO, Money::plus);
        s.adjustmentsChanged(total);
        statements.save(s);
        businessEvents.log(statementEvent(BusinessEventTypes.SETTLEMENT_ADJUSTED, s).amount(adjustment.getAmount())
                .attr("adjustmentId", adjustment.getId()).attr("category", adjustment.getCategory())
                .attr("dualApproval", adjustment.getDecidedBy() != null)
                .attr("relatedStatementId", adjustment.getRelatedStatementId()));
    }

    private BusinessEvent statementEvent(String type, SettlementStatement s) {
        return BusinessEvent.of(type).creditor(s.getCreditorId()).reference(RESOURCE, s.getId())
                .attr("sequenceNumber", s.getSequenceNumber()).attr("periodStart", s.getPeriodStart().toString())
                .attr("periodEnd", s.getPeriodEnd().toString());
    }

    private SettlementAdjustment loadAdjustment(UUID id) {
        return statements.findAdjustment(id).orElseThrow(() -> new ResourceNotFoundException("settlement_adjustment", id));
    }

    /** Leitura com bloqueio pessimista e escopo de comando. */
    private SettlementStatement loadForCommand(UUID id) {
        SettlementStatement s = statements.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        accessScope.requireVisible(RESOURCE, id, s.getCreditorId());
        accessScope.requireCreditor(s.getCreditorId());
        return s;
    }

    private SettlementStatement loadVisible(UUID id) {
        SettlementStatement s = statements.findById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        accessScope.requireVisible(RESOURCE, id, s.getCreditorId());
        return s;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }
}
