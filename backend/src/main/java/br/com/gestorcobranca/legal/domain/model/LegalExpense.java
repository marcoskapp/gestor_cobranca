package br.com.gestorcobranca.legal.domain.model;

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
 * Custa ou despesa do caso jurídico: tipo, valor, data, quem adiantou, se é reembolsável (repasse
 * ao credor no demonstrativo ou composição no acordo), comprovante (documento do caso) e situação.
 * Nada é apagado: o cancelamento mantém o lançamento (colunas financeiras imutáveis no banco).
 */
@Entity
@Table(name = "legal_expense", schema = "legal")
public class LegalExpense extends TenantScopedEntity {

    @Column(name = "matter_id", nullable = false, updatable = false)
    private UUID matterId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "lawsuit_id", updatable = false)
    private UUID lawsuitId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30, updatable = false)
    private ExpenseType type;

    @Column(name = "description", length = 300, updatable = false)
    private String description;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money amount;

    @Column(name = "incurred_on", nullable = false, updatable = false)
    private LocalDate incurredOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "advanced_by", nullable = false, length = 20, updatable = false)
    private ExpenseAdvancer advancedBy;

    @Column(name = "law_firm_id", updatable = false)
    private UUID lawFirmId;

    @Column(name = "reimbursable", nullable = false, updatable = false)
    private boolean reimbursable;

    @Column(name = "receipt_document_id", updatable = false)
    private UUID receiptDocumentId;

    @Column(name = "over_budget", nullable = false, updatable = false)
    private boolean overBudget;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ExpenseStatus status;

    @Column(name = "status_date")
    private LocalDate statusDate;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    protected LegalExpense() {
    }

    public LegalExpense(UUID matterId, UUID creditorId, UUID lawsuitId, ExpenseType type, String description,
                        Money amount, LocalDate incurredOn, ExpenseAdvancer advancedBy, UUID lawFirmId,
                        boolean reimbursable, UUID receiptDocumentId, boolean overBudget, LocalDate today) {
        Guard.require(type != null, "legal_expense.type_required", "Tipo da despesa obrigatório");
        Guard.require(amount != null && amount.isPositive(), "legal_expense.amount_invalid", "Valor deve ser positivo");
        Guard.require(incurredOn != null && !incurredOn.isAfter(today), "legal_expense.date_invalid",
                "Data da despesa obrigatória e não futura");
        Guard.require(advancedBy != null, "legal_expense.advanced_by_required", "Informe quem adiantou a despesa");
        Guard.require(advancedBy != ExpenseAdvancer.LAW_FIRM || lawFirmId != null, "legal_expense.law_firm_required",
                "Despesa adiantada por escritório exige caso atribuído a escritório parceiro");
        Guard.require(type != ExpenseType.OTHER || (description != null && !description.isBlank()),
                "legal_expense.description_required", "Descreva a despesa");
        Guard.require(description == null || description.trim().length() <= 300, "legal_expense.description_too_long",
                "Descrição até 300 caracteres");
        this.matterId = matterId;
        this.creditorId = creditorId;
        this.lawsuitId = lawsuitId;
        this.type = type;
        this.description = description == null || description.isBlank() ? null : description.trim();
        this.amount = amount;
        this.incurredOn = incurredOn;
        this.advancedBy = advancedBy;
        this.lawFirmId = advancedBy == ExpenseAdvancer.LAW_FIRM ? lawFirmId : null;
        this.reimbursable = reimbursable;
        this.receiptDocumentId = receiptDocumentId;
        this.overBudget = overBudget;
        this.status = ExpenseStatus.RECORDED;
    }

    public void reimburse(LocalDate on, LocalDate today, String note) {
        Guard.requireState(status == ExpenseStatus.RECORDED, "legal_expense.not_recorded", "Despesa não está lançada");
        Guard.requireState(reimbursable, "legal_expense.not_reimbursable", "Despesa não é reembolsável");
        Guard.requireState(advancedBy != ExpenseAdvancer.CREDITOR, "legal_expense.creditor_paid",
                "Despesa paga pelo credor não tem reembolso a quem adiantou");
        Guard.require(on != null && !on.isAfter(today) && !on.isBefore(incurredOn), "legal_expense.date_invalid",
                "Data do reembolso inválida");
        this.status = ExpenseStatus.REIMBURSED;
        this.statusDate = on;
        this.statusReason = text(note);
    }

    public void cancel(String reason, LocalDate today) {
        Guard.requireState(status == ExpenseStatus.RECORDED, "legal_expense.not_recorded",
                "Somente despesa lançada (não reembolsada) pode ser cancelada");
        Guard.require(reason != null && !reason.isBlank(), "legal_expense.reason_required", "Motivo obrigatório");
        this.status = ExpenseStatus.CANCELLED;
        this.statusDate = today;
        this.statusReason = text(reason);
    }

    public boolean counts() {
        return status != ExpenseStatus.CANCELLED;
    }

    private static String text(String value) {
        String v = value == null || value.isBlank() ? null : value.trim();
        Guard.require(v == null || v.length() <= 500, "legal_expense.reason_too_long", "Texto até 500 caracteres");
        return v;
    }

    public UUID getMatterId() {
        return matterId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getLawsuitId() {
        return lawsuitId;
    }

    public ExpenseType getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public Money getAmount() {
        return amount;
    }

    public LocalDate getIncurredOn() {
        return incurredOn;
    }

    public ExpenseAdvancer getAdvancedBy() {
        return advancedBy;
    }

    public UUID getLawFirmId() {
        return lawFirmId;
    }

    public boolean isReimbursable() {
        return reimbursable;
    }

    public UUID getReceiptDocumentId() {
        return receiptDocumentId;
    }

    public boolean isOverBudget() {
        return overBudget;
    }

    public ExpenseStatus getStatus() {
        return status;
    }

    public LocalDate getStatusDate() {
        return statusDate;
    }

    public String getStatusReason() {
        return statusReason;
    }
}
