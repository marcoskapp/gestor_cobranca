package br.com.gestorcobranca.settlement.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Custa ou despesa do jurídico no demonstrativo ({@code LegalExpenseChanged}). Reembolsável e
 * adiantada pela assessoria ou por escritório parceiro → deduzida do valor a repassar
 * ({@link Effect#DEDUCT}); paga pelo credor ou não reembolsável → só informativa. Cancelamento de
 * despesa já incluída em demonstrativo gera um lançamento {@code CANCELLATION} negativo (devolvido ao
 * credor no demonstrativo seguinte); antes da inclusão, a despesa só sai.
 */
@Entity
@Table(name = "reimbursable_expense", schema = "settlement")
public class ReimbursableExpense extends TenantScopedEntity {

    public enum Kind { CHARGE, CANCELLATION }

    public enum Effect { DEDUCT, INFORMATIVE }

    public enum Status { ACTIVE, CANCELLED, REIMBURSED_OUTSIDE }

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 20)
    private Kind kind;
    @Column(name = "legal_expense_id", nullable = false, updatable = false)
    private UUID legalExpenseId;
    @Column(name = "matter_id", nullable = false, updatable = false)
    private UUID matterId;
    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;
    @Column(name = "expense_type", nullable = false, updatable = false, length = 30)
    private String expenseType;
    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money amount;
    @Column(name = "incurred_on", nullable = false, updatable = false)
    private LocalDate incurredOn;
    @Column(name = "advanced_by", nullable = false, updatable = false, length = 20)
    private String advancedBy;
    @Column(name = "reimbursable", nullable = false, updatable = false)
    private boolean reimbursable;
    @Column(name = "has_receipt", nullable = false, updatable = false)
    private boolean hasReceipt;
    @Enumerated(EnumType.STRING)
    @Column(name = "effect", nullable = false, updatable = false, length = 20)
    private Effect effect;
    @Column(name = "competence_date", nullable = false, updatable = false)
    private LocalDate competenceDate;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private Status status;
    @Column(name = "reverses_expense_id", updatable = false)
    private UUID reversesExpenseId;
    @Column(name = "statement_id")
    private UUID statementId;

    protected ReimbursableExpense() {
    }

    public static ReimbursableExpense charge(UUID legalExpenseId, UUID matterId, UUID creditorId, String type,
                                             Money amount, LocalDate incurredOn, String advancedBy,
                                             boolean reimbursable, boolean hasReceipt) {
        Guard.require(amount != null && amount.isPositive(), "expense.amount_invalid", "Despesa deve ser positiva");
        ReimbursableExpense e = new ReimbursableExpense();
        e.kind = Kind.CHARGE;
        e.legalExpenseId = legalExpenseId;
        e.matterId = matterId;
        e.creditorId = creditorId;
        e.expenseType = type;
        e.amount = amount;
        e.incurredOn = incurredOn;
        e.advancedBy = advancedBy;
        e.reimbursable = reimbursable;
        e.hasReceipt = hasReceipt;
        e.effect = reimbursable && !"CREDITOR".equals(advancedBy) ? Effect.DEDUCT : Effect.INFORMATIVE;
        e.competenceDate = incurredOn;
        e.status = Status.ACTIVE;
        return e;
    }

    public ReimbursableExpense cancellation(LocalDate on) {
        Guard.requireState(kind == Kind.CHARGE, "expense.not_charge", "Somente despesa lançada é cancelada");
        ReimbursableExpense c = new ReimbursableExpense();
        c.kind = Kind.CANCELLATION;
        c.legalExpenseId = legalExpenseId;
        c.matterId = matterId;
        c.creditorId = creditorId;
        c.expenseType = expenseType;
        c.amount = amount.negate();
        c.incurredOn = incurredOn;
        c.advancedBy = advancedBy;
        c.reimbursable = reimbursable;
        c.hasReceipt = hasReceipt;
        c.effect = effect;
        c.competenceDate = on;
        c.status = Status.ACTIVE;
        c.reversesExpenseId = getId();
        return c;
    }

    public void cancelBeforeStatement() {
        Guard.requireState(statementId == null, "expense.in_statement", "Despesa já incluída em demonstrativo");
        this.status = Status.CANCELLED;
    }

    public void reimbursedOutside() {
        Guard.requireState(statementId == null, "expense.in_statement", "Despesa já incluída em demonstrativo");
        this.status = Status.REIMBURSED_OUTSIDE;
    }

    /** Valor deduzido do repasse (positivo = credor reembolsa a assessoria). */
    public Money deduction() {
        return effect == Effect.DEDUCT && status == Status.ACTIVE ? amount : Money.ZERO;
    }

    public void bindTo(UUID statement) {
        this.statementId = statement;
    }

    public void unbind() {
        this.statementId = null;
    }

    public Kind getKind() { return kind; }
    public UUID getLegalExpenseId() { return legalExpenseId; }
    public UUID getMatterId() { return matterId; }
    public UUID getCreditorId() { return creditorId; }
    public String getExpenseType() { return expenseType; }
    public Money getAmount() { return amount; }
    public LocalDate getIncurredOn() { return incurredOn; }
    public String getAdvancedBy() { return advancedBy; }
    public boolean isReimbursable() { return reimbursable; }
    public boolean isHasReceipt() { return hasReceipt; }
    public Effect getEffect() { return effect; }
    public LocalDate getCompetenceDate() { return competenceDate; }
    public Status getStatus() { return status; }
    public UUID getReversesExpenseId() { return reversesExpenseId; }
    public UUID getStatementId() { return statementId; }
}
