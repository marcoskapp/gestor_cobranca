package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Tarefa do caso (retorno ao devedor ou acompanhamento) com data/hora (instante UTC, informado
 * pelo usuário no fuso de operação). Sem responsável, a tarefa é da fila do caso.
 */
@Entity
@Table(name = "task", schema = "collection")
public class CollectionTask extends TenantScopedEntity {

    public static final int NOTE_MAX = 1000;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private TaskType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TaskStatus status;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(name = "assignee_user_id")
    private UUID assigneeUserId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "note_encrypted", length = 6000)
    private String note;

    @Column(name = "origin_interaction_id", updatable = false)
    private UUID originInteractionId;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "completed_by")
    private UUID completedBy;

    @Column(name = "completion_interaction_id")
    private UUID completionInteractionId;

    @Column(name = "missed_at")
    private Instant missedAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    protected CollectionTask() {
    }

    public CollectionTask(CollectionCase c, TaskType type, Instant dueAt, UUID assigneeUserId, String note,
                          UUID originInteractionId, Instant now) {
        Guard.require(type != null, "task.type_required", "Tipo da tarefa obrigatório");
        Guard.require(dueAt != null, "task.due_required", "Data e hora da tarefa obrigatórias");
        Guard.require(dueAt.isAfter(now), "task.due_in_past", "Data e hora da tarefa devem estar no futuro");
        Guard.requireState(c.getStatus().isActive(), "case.closed", "Caso encerrado");
        String clean = note == null || note.isBlank() ? null : note.trim();
        Guard.require(clean == null || clean.length() <= NOTE_MAX, "task.note_too_long",
                "Observação com mais de %d caracteres".formatted(NOTE_MAX));
        this.caseId = c.getId();
        this.creditorId = c.getCreditorId();
        this.type = type;
        this.status = TaskStatus.PENDING;
        this.dueAt = dueAt;
        this.assigneeUserId = assigneeUserId;
        this.note = clean;
        this.originInteractionId = originInteractionId;
    }

    public void complete(UUID by, UUID interactionId, Instant now) {
        Guard.requireState(status.isOpen(), "task.not_open", "Tarefa já concluída ou cancelada");
        this.status = TaskStatus.DONE;
        this.completedAt = now;
        this.completedBy = by;
        this.completionInteractionId = interactionId;
    }

    public void cancel(String reason) {
        Guard.requireState(status.isOpen(), "task.not_open", "Tarefa já concluída ou cancelada");
        Guard.require(reason != null && !reason.isBlank() && reason.trim().length() <= 500, "task.reason_required",
                "Motivo do cancelamento obrigatório (até 500 caracteres)");
        this.status = TaskStatus.CANCELLED;
        this.cancelReason = reason.trim();
    }

    public void reschedule(Instant newDueAt, Instant now) {
        Guard.requireState(status.isOpen(), "task.not_open", "Tarefa já concluída ou cancelada");
        Guard.require(newDueAt != null && newDueAt.isAfter(now), "task.due_in_past",
                "Data e hora da tarefa devem estar no futuro");
        this.dueAt = newDueAt;
        this.status = TaskStatus.PENDING;
        this.missedAt = null;
    }

    /** Vencida além da tolerância sem conclusão. */
    public void markMissed(Instant now) {
        Guard.requireState(status == TaskStatus.PENDING, "task.not_pending", "Tarefa não está pendente");
        this.status = TaskStatus.MISSED;
        this.missedAt = now;
    }

    public void reassign(UUID userId) {
        Guard.requireState(status.isOpen(), "task.not_open", "Tarefa já concluída ou cancelada");
        this.assigneeUserId = userId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public TaskType getType() {
        return type;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public UUID getAssigneeUserId() {
        return assigneeUserId;
    }

    public String getNote() {
        return note;
    }

    public UUID getOriginInteractionId() {
        return originInteractionId;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public UUID getCompletedBy() {
        return completedBy;
    }

    public UUID getCompletionInteractionId() {
        return completionInteractionId;
    }

    public Instant getMissedAt() {
        return missedAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }
}
