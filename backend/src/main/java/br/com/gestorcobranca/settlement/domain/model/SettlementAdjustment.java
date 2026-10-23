package br.com.gestorcobranca.settlement.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Ajuste manual do demonstrativo com motivo obrigatório: {@code amount} é o efeito com sinal no valor a
 * repassar (positivo a favor do credor). Até o limite, aplicado na hora; acima, exige segunda aprovação
 * por outra pessoa com {@code SETTLEMENT_APPROVE}. Correções de demonstrativo já aprovado entram como
 * ajuste no período seguinte, referenciando o demonstrativo corrigido.
 */
@Entity
@Table(name = "statement_adjustment", schema = "settlement")
public class SettlementAdjustment extends TenantScopedEntity {

    public enum Category { COMMISSION_CORRECTION, PRIOR_PERIOD_CORRECTION, EXPENSE_REIMBURSEMENT, CONTESTATION_SETTLEMENT, OTHER }

    public enum Status { APPLIED, PENDING_SECOND_APPROVAL, REJECTED }

    @Column(name = "statement_id", nullable = false, updatable = false)
    private UUID statementId;
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, updatable = false, length = 40)
    private Category category;
    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money amount;
    @Column(name = "reason", nullable = false, updatable = false, length = 2000)
    private String reason;
    @Column(name = "related_statement_id", updatable = false)
    private UUID relatedStatementId;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private Status status;
    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;
    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;
    @Column(name = "decided_by")
    private UUID decidedBy;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "decision_note", length = 2000)
    private String decisionNote;

    protected SettlementAdjustment() {
    }

    public SettlementAdjustment(UUID statementId, Category category, Money amount, String reason, UUID relatedStatementId,
                               boolean requiresSecondApproval, UUID requestedBy, Instant at) {
        Guard.require(category != null, "adjustment.category_required", "Categoria do ajuste obrigatória");
        Guard.require(amount != null && !amount.isZero(), "adjustment.amount_invalid", "Ajuste não pode ser zero");
        Guard.require(reason != null && reason.trim().length() >= 10 && reason.length() <= 2000,
                "adjustment.reason_required", "Motivo do ajuste obrigatório (10 a 2000 caracteres)");
        Guard.require(category != Category.PRIOR_PERIOD_CORRECTION || relatedStatementId != null,
                "adjustment.related_statement_required",
                "Correção de período anterior deve indicar o demonstrativo corrigido");
        this.statementId = statementId;
        this.category = category;
        this.amount = amount;
        this.reason = reason.trim();
        this.relatedStatementId = relatedStatementId;
        this.status = requiresSecondApproval ? Status.PENDING_SECOND_APPROVAL : Status.APPLIED;
        this.requestedBy = requestedBy;
        this.requestedAt = at;
    }

    public void approve(UUID by, String note, Instant at) {
        decide(by, note, at);
        this.status = Status.APPLIED;
    }

    public void reject(UUID by, String note, Instant at) {
        Guard.require(note != null && !note.isBlank(), "adjustment.decision_note_required", "Informe o motivo da recusa");
        decide(by, note, at);
        this.status = Status.REJECTED;
    }

    private void decide(UUID by, String note, Instant at) {
        Guard.requireState(status == Status.PENDING_SECOND_APPROVAL, "adjustment.not_pending",
                "Ajuste não aguarda segunda aprovação");
        Guard.require(!by.equals(requestedBy), "adjustment.self_approval",
                "A segunda aprovação deve ser de outra pessoa");
        Guard.require(note == null || note.length() <= 2000, "adjustment.note_too_long", "Observação até 2000 caracteres");
        this.decidedBy = by;
        this.decidedAt = at;
        this.decisionNote = note == null || note.isBlank() ? null : note.trim();
    }

    public UUID getStatementId() { return statementId; }
    public Category getCategory() { return category; }
    public Money getAmount() { return amount; }
    public String getReason() { return reason; }
    public UUID getRelatedStatementId() { return relatedStatementId; }
    public Status getStatus() { return status; }
    public UUID getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public UUID getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionNote() { return decisionNote; }
}
