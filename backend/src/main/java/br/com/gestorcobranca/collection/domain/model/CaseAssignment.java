package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Histórico (append-only) das mudanças de dono do atendimento do caso: base para medir tempo
 * em fila, produtividade por operador e rastrear redistribuições.
 */
@Entity
@Table(name = "case_assignment", schema = "collection")
public class CaseAssignment extends TenantScopedEntity {

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 30, updatable = false)
    private AssignmentMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_owner_type", nullable = false, length = 20, updatable = false)
    private OwnerType previousOwnerType;

    @Column(name = "previous_user_id", updatable = false)
    private UUID previousUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 20, updatable = false)
    private OwnerType ownerType;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "team_id", updatable = false)
    private UUID teamId;

    @Column(name = "queue_id", updatable = false)
    private UUID queueId;

    @Column(name = "reason", length = 500, updatable = false)
    private String reason;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected CaseAssignment() {
    }

    public CaseAssignment(CollectionCase c, AssignmentMode mode, OwnerType previousOwnerType, UUID previousUserId,
                          String reason, Instant occurredAt) {
        Guard.require(mode != null, "assignment.mode_required", "Origem da atribuição obrigatória");
        this.caseId = c.getId();
        this.creditorId = c.getCreditorId();
        this.mode = mode;
        this.previousOwnerType = previousOwnerType;
        this.previousUserId = previousUserId;
        this.ownerType = c.getOwnerType();
        this.userId = c.getAssigneeUserId();
        this.teamId = c.getAssigneeTeamId();
        this.queueId = c.getQueueId();
        this.reason = reason == null || reason.isBlank() ? null
                : reason.trim().length() > 500 ? reason.trim().substring(0, 500) : reason.trim();
        this.occurredAt = occurredAt;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public AssignmentMode getMode() {
        return mode;
    }

    public OwnerType getPreviousOwnerType() {
        return previousOwnerType;
    }

    public UUID getPreviousUserId() {
        return previousUserId;
    }

    public OwnerType getOwnerType() {
        return ownerType;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public UUID getQueueId() {
        return queueId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
