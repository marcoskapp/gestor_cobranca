package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.legal.application.port.in.LegalExpenseUseCases;
import br.com.gestorcobranca.legal.domain.model.BudgetEnforcement;
import br.com.gestorcobranca.legal.domain.model.CreditorAuthorization;
import br.com.gestorcobranca.legal.domain.model.ExpenseAdvancer;
import br.com.gestorcobranca.legal.domain.model.ExpenseStatus;
import br.com.gestorcobranca.legal.domain.model.ExpenseType;
import br.com.gestorcobranca.legal.domain.model.LegalExpense;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.model.LegalSettings;
import br.com.gestorcobranca.legal.domain.repository.LawsuitRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalDocumentRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalExpenseRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalMatterRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.legal.LegalExpenseChanged;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
class LegalExpenseService implements LegalExpenseUseCases {

    static final String ENTITY = "legal_expense";

    private final LegalExpenseRepository expenses;
    private final LegalMatterRepository matters;
    private final LawsuitRepository lawsuits;
    private final LegalDocumentRepository documents;
    private final MatterRequirements requirements;
    private final LegalSettingsProvider settings;
    private final MatterRecorder matterRecorder;
    private final LegalAccess access;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final LegalMetrics metrics;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    LegalExpenseService(LegalExpenseRepository expenses, LegalMatterRepository matters, LawsuitRepository lawsuits,
                        LegalDocumentRepository documents, MatterRequirements requirements,
                        LegalSettingsProvider settings, MatterRecorder matterRecorder, LegalAccess access,
                        AuditTrail auditTrail, BusinessEventLogger businessEvents, LegalMetrics metrics,
                        ApplicationEventPublisher events, Clock clock) {
        this.expenses = expenses;
        this.matters = matters;
        this.lawsuits = lawsuits;
        this.documents = documents;
        this.requirements = requirements;
        this.settings = settings;
        this.matterRecorder = matterRecorder;
        this.access = access;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public ExpenseView record(UUID matterId, ExpenseCommand c) {
        LegalMatter m = access.forCommand(matterId);
        m.requireOpen();
        LegalSettings.Values values = settings.current();
        ExpenseType type = LegalViews.parse(ExpenseType.class, c.type(), "legal_expense.type_invalid", "Tipo da despesa");
        ExpenseAdvancer advancer = LegalViews.parse(ExpenseAdvancer.class, c.advancedBy(), "legal_expense.advanced_by_invalid",
                "Quem adiantou");
        if (!values.allowedExpenseAdvancers().contains(advancer)) {
            metrics.expenseBlocked("advancer_not_allowed");
            throw new BusinessRuleException("legal_expense.advancer_not_allowed",
                    "Os parâmetros do jurídico não permitem custas adiantadas por " + advancer);
        }
        Guard.require(advancer != ExpenseAdvancer.LAW_FIRM || m.getLawFirmId() != null, "legal_expense.law_firm_required",
                "Despesa adiantada por escritório exige caso atribuído a escritório parceiro");
        Guard.require(c.amount() != null && c.amount().signum() > 0, "legal_expense.amount_invalid", "Valor deve ser positivo");
        Money amount = Money.of(c.amount());
        if (c.lawsuitId() != null) {
            lawsuits.findById(c.lawsuitId()).filter(l -> l.getMatterId().equals(m.getId()))
                    .orElseThrow(() -> new BusinessRuleException("legal_expense.lawsuit_invalid", "Processo de outro caso jurídico"));
        }
        if (c.receiptDocumentId() != null) {
            documents.findById(c.receiptDocumentId()).filter(d -> d.getMatterId().equals(m.getId()))
                    .orElseThrow(() -> new BusinessRuleException("legal_expense.receipt_invalid",
                            "Comprovante deve ser documento do caso jurídico"));
        }
        Optional<CreditorAuthorization> granted = requirements.granted(m.getId());
        boolean overBudget = granted.isEmpty()
                || m.getExpensesTotal().plus(amount).isGreaterThan(granted.get().getApprovedBudget());
        if (overBudget && values.budgetEnforcement() == BudgetEnforcement.BLOCK) {
            if (granted.isEmpty()) {
                metrics.expenseBlocked("no_authorization");
                throw new ConflictException("legal_expense.authorization_required",
                        "Despesas exigem autorização do credor com orçamento aprovado");
            }
            metrics.expenseBlocked("budget_exceeded");
            throw new BusinessRuleException("legal_expense.budget_exceeded",
                    "Despesa ultrapassa o orçamento aprovado pelo credor; solicite autorização suplementar");
        }
        LocalDate today = LegalViews.today(clock);
        LegalExpense e = expenses.save(new LegalExpense(m.getId(), m.getCreditorId(), c.lawsuitId(), type, c.description(),
                amount, c.incurredOn(), advancer, m.getLawFirmId(), c.reimbursable(), c.receiptDocumentId(), overBudget,
                today));
        m.addExpense(amount);
        matters.save(m);
        auditTrail.record(AuditEntry.of("legal_expense.recorded", ENTITY, e.getId()).creditor(m.getCreditorId())
                .created("matterId", m.getId()).created("type", e.getType()).created("amount", e.getAmount())
                .created("incurredOn", e.getIncurredOn()).created("advancedBy", e.getAdvancedBy())
                .created("reimbursable", e.isReimbursable()).created("overBudget", e.isOverBudget())
                .created("receiptDocumentId", e.getReceiptDocumentId()));
        businessEvents.log(matterRecorder.base(BusinessEventTypes.LEGAL_EXPENSE_RECORDED, m).amount(e.getAmount())
                .reference(ENTITY, e.getId()).attr("matterId", m.getId()).attr("type", e.getType())
                .attr("advancedBy", e.getAdvancedBy()).attr("reimbursable", e.isReimbursable())
                .attr("overBudget", e.isOverBudget()).attr("hasReceipt", e.getReceiptDocumentId() != null)
                .attr("expensesTotal", m.getExpensesTotal().amount())
                .attr("authorizedBudget", m.getAuthorizedBudget() == null ? null : m.getAuthorizedBudget().amount())
                .attr("stage", m.getStage()));
        metrics.expense(e.getType(), e.getAdvancedBy(), e.isOverBudget());
        publishChanged(e);
        return LegalViews.expense(e);
    }

    @Override
    public ExpenseView cancel(UUID expenseId, String reason) {
        LegalExpense e = load(expenseId);
        LegalMatter m = access.forCommand(e.getMatterId());
        e.cancel(reason, LegalViews.today(clock));
        expenses.save(e);
        m.removeExpense(e.getAmount());
        matters.save(m);
        auditTrail.record(AuditEntry.of("legal_expense.cancelled", ENTITY, e.getId()).creditor(m.getCreditorId())
                .reason(e.getStatusReason()).change("status", ExpenseStatus.RECORDED, e.getStatus()));
        businessEvents.log(matterRecorder.base(BusinessEventTypes.LEGAL_EXPENSE_CANCELLED, m).amount(e.getAmount())
                .reference(ENTITY, e.getId()).attr("matterId", m.getId()).attr("type", e.getType()));
        publishChanged(e);
        return LegalViews.expense(e);
    }

    @Override
    public ExpenseView reimburse(UUID expenseId, LocalDate reimbursedOn, String note) {
        LegalExpense e = load(expenseId);
        LegalMatter m = access.forCommand(e.getMatterId());
        e.reimburse(reimbursedOn, LegalViews.today(clock), note);
        expenses.save(e);
        auditTrail.record(AuditEntry.of("legal_expense.reimbursed", ENTITY, e.getId()).creditor(m.getCreditorId())
                .change("status", ExpenseStatus.RECORDED, e.getStatus()).meta("reimbursedOn", e.getStatusDate()));
        businessEvents.log(matterRecorder.base(BusinessEventTypes.LEGAL_EXPENSE_REIMBURSED, m).amount(e.getAmount())
                .reference(ENTITY, e.getId()).attr("matterId", m.getId()).attr("type", e.getType())
                .attr("advancedBy", e.getAdvancedBy())
                .attr("daysToReimburse", java.time.temporal.ChronoUnit.DAYS.between(e.getIncurredOn(), e.getStatusDate())));
        publishChanged(e);
        return LegalViews.expense(e);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpenseView> list(UUID matterId) {
        LegalMatter m = access.visible(matterId);
        return expenses.findByMatterIdOrderByIncurredOnDescCreatedAtDesc(m.getId()).stream().map(LegalViews::expense).toList();
    }

    /** Contrato com a prestação de contas (settlement): despesas reembolsáveis no demonstrativo do credor. */
    private void publishChanged(LegalExpense e) {
        events.publishEvent(new LegalExpenseChanged(Ids.newId(), e.getTenantId(), Instant.now(clock), e.getId(),
                e.getMatterId(), e.getCreditorId(), e.getType().name(), e.getAmount().amount(), e.getIncurredOn(),
                e.getAdvancedBy().name(), e.isReimbursable(), e.getReceiptDocumentId() != null, e.getStatus().name(),
                e.getStatusDate()));
    }

    private LegalExpense load(UUID id) {
        return expenses.findById(id).orElseThrow(() -> ResourceNotFoundException.of(ENTITY, id));
    }
}
