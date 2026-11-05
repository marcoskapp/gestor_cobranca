package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/** Mudança do responsável de uma contestação (append-only): de quem, para quem, como, por quem e por quê. */
@Entity
@Immutable
@Table(name = "dispute_assignment", schema = "collection")
public class DisputeAssignment extends TenantScopedEntity {

    @Column(name = "dispute_id", nullable = false, updatable = false)
    private UUID disputeId;

    @Column(name = "previous_assignee_id", updatable = false)
    private UUID previousAssigneeId;

    @Column(name = "assignee_id", updatable = false)
    private UUID assigneeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20, updatable = false)
    private DisputeAssignmentMode mode;

    @Column(name = "reason", length = 500, updatable = false)
    private String reason;

    @Column(name = "assigned_by", nullable = false, updatable = false)
    private UUID assignedBy;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    protected DisputeAssignment() {
    }

    public DisputeAssignment(UUID disputeId, UUID previousAssigneeId, UUID assigneeId, DisputeAssignmentMode mode,
                             String reason, UUID assignedBy, Instant assignedAt) {
        Guard.require(mode != null, "dispute.assignment_mode_required", "Modo da atribuição obrigatório");
        Guard.require(reason == null || reason.length() <= 500, "dispute.assignment_reason_too_long",
                "Motivo até 500 caracteres");
        this.disputeId = disputeId;
        this.previousAssigneeId = previousAssigneeId;
        this.assigneeId = assigneeId;
        this.mode = mode;
        this.reason = reason == null || reason.isBlank() ? null : reason.trim();
        this.assignedBy = assignedBy;
        this.assignedAt = assignedAt;
    }

    public UUID getDisputeId() {
        return disputeId;
    }

    public UUID getPreviousAssigneeId() {
        return previousAssigneeId;
    }

    public UUID getAssigneeId() {
        return assigneeId;
    }

    public DisputeAssignmentMode getMode() {
        return mode;
    }

    public String getReason() {
        return reason;
    }

    public UUID getAssignedBy() {
        return assignedBy;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }
}
