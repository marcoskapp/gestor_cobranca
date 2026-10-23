package br.com.gestorcobranca.incentive.domain.model.statement;

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
 * Ajuste manual de extrato (positivo ou negativo), com motivo obrigatório e auditado. Acima do
 * limite configurado exige segunda aprovação por outra pessoa com a mesma permissão.
 */
@Entity
@Table(name = "statement_adjustment", schema = "incentive")
public class StatementAdjustment extends TenantScopedEntity {

    @Column(name = "statement_id", nullable = false, updatable = false)
    private UUID statementId;

    @Column(name = "dispute_id", updatable = false)
    private UUID disputeId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money amount;

    @Column(name = "reason", nullable = false, updatable = false, length = 2000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private AdjustmentStatus status;

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

    protected StatementAdjustment() {
    }

    /** @param dualApprovalThreshold valor absoluto a partir do qual exige segunda aprovação */
    public StatementAdjustment(UUID statementId, UUID disputeId, Money amount, String reason, Money dualApprovalThreshold,
                               UUID requestedBy, Instant now) {
        Guard.require(amount != null && !amount.isZero(), "adjustment.amount_invalid", "Valor do ajuste deve ser diferente de zero");
        Guard.require(reason != null && reason.trim().length() >= 10 && reason.length() <= 2000, "adjustment.reason_invalid",
                "Motivo obrigatório (10 a 2000 caracteres)");
        this.statementId = statementId;
        this.disputeId = disputeId;
        this.amount = amount;
        this.reason = reason.trim();
        this.requestedBy = requestedBy;
        this.requestedAt = now;
        this.status = amount.abs().isGreaterThan(dualApprovalThreshold) ? AdjustmentStatus.PENDING_SECOND_APPROVAL
                : AdjustmentStatus.APPLIED;
    }

    public void decide(boolean approve, String note, UUID actorId, Instant now) {
        Guard.requireState(status == AdjustmentStatus.PENDING_SECOND_APPROVAL, "adjustment.not_pending",
                "Ajuste não aguarda aprovação");
        Guard.require(!actorId.equals(requestedBy), "adjustment.same_approver",
                "A segunda aprovação deve ser feita por outra pessoa");
        Guard.require(approve || (note != null && note.trim().length() >= 10), "adjustment.note_required",
                "Justifique a recusa (mínimo 10 caracteres)");
        this.status = approve ? AdjustmentStatus.APPLIED : AdjustmentStatus.REJECTED;
        this.decidedBy = actorId;
        this.decidedAt = now;
        this.decisionNote = note == null ? null : note.trim();
    }

    public boolean isApplied() {
        return status == AdjustmentStatus.APPLIED;
    }

    public boolean isPending() {
        return status == AdjustmentStatus.PENDING_SECOND_APPROVAL;
    }

    public UUID getStatementId() {
        return statementId;
    }

    public UUID getDisputeId() {
        return disputeId;
    }

    public Money getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public AdjustmentStatus getStatus() {
        return status;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionNote() {
        return decisionNote;
    }
}
