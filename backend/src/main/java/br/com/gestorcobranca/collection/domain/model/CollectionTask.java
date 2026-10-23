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

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Tarefa do caso (retorno ao devedor ou acompanhamento) com data/hora (instante UTC, informado
 * pelo usuário no fuso de operação {@code TimeConfig.BUSINESS_ZONE}). Sem responsável, a tarefa
 * é da fila do caso.
 * <p>
 * Cumprimento: concluída até {@code toleranceUntil} (horário combinado + tolerância, fixada no
 * agendamento) conta como <b>cumprida</b>; passada a tolerância sem conclusão, o job de vencimento
 * a marca {@code MISSED} (atrasada) e ela ainda pode ser concluída depois (cumprimento tardio).
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

    @Column(name = "tolerance_until", nullable = false)
    private Instant toleranceUntil;

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

    public CollectionTask(CollectionCase c, TaskType type, Instant dueAt, Duration tolerance, UUID assigneeUserId,
                          String note, UUID originInteractionId, Instant now) {
        Guard.require(type != null, "task.type_required", "Tipo da tarefa obrigatório");
        Guard.require(dueAt != null, "task.due_required", "Data e hora da tarefa obrigatórias");
        Guard.require(dueAt.isAfter(now), "task.due_in_past", "Data e hora da tarefa devem estar no futuro");
        Guard.require(tolerance != null && !tolerance.isNegative(), "task.tolerance_invalid", "Tolerância inválida");
        Guard.requireState(c.getStatus().isActive(), "case.closed", "Caso encerrado");
        String clean = note == null || note.isBlank() ? null : note.trim();
        Guard.require(clean == null || clean.length() <= NOTE_MAX, "task.note_too_long",
                "Observação com mais de %d caracteres".formatted(NOTE_MAX));
        this.caseId = c.getId();
        this.creditorId = c.getCreditorId();
        this.type = type;
        this.status = TaskStatus.PENDING;
        this.dueAt = dueAt;
        this.toleranceUntil = dueAt.plus(tolerance);
        this.assigneeUserId = assigneeUserId;
        this.note = clean;
        this.originInteractionId = originInteractionId;
    }

    /** @return true se cumprida no prazo (até o fim da tolerância e sem ter sido marcada atrasada) */
    public boolean complete(UUID by, UUID interactionId, Instant now) {
        Guard.requireState(status.isOpen(), "task.not_open", "Tarefa já concluída ou cancelada");
        boolean onTime = status == TaskStatus.PENDING && !now.isAfter(toleranceUntil);
        this.status = TaskStatus.DONE;
        this.completedAt = now;
        this.completedBy = by;
        this.completionInteractionId = interactionId;
        return onTime;
    }

    public void cancel(String reason) {
        Guard.requireState(status.isOpen(), "task.not_open", "Tarefa já concluída ou cancelada");
        Guard.require(reason != null && !reason.isBlank() && reason.trim().length() <= 500, "task.reason_required",
                "Motivo do cancelamento obrigatório (até 500 caracteres)");
        this.status = TaskStatus.CANCELLED;
        this.cancelReason = reason.trim();
    }

    /**
     * Novo horário combinado: é um novo compromisso (volta a pendente, com nova tolerância). Um
     * atraso anterior já foi comunicado e permanece nos indicadores.
     */
    public void reschedule(Instant newDueAt, Duration tolerance, Instant now) {
        Guard.requireState(status.isOpen(), "task.not_open", "Tarefa já concluída ou cancelada");
        Guard.require(newDueAt != null && newDueAt.isAfter(now), "task.due_in_past",
                "Data e hora da tarefa devem estar no futuro");
        this.dueAt = newDueAt;
        this.toleranceUntil = newDueAt.plus(tolerance);
        this.status = TaskStatus.PENDING;
        this.missedAt = null;
    }

    /**
     * Vencida além da tolerância sem conclusão.
     *
     * @return true se passou a atrasada agora
     */
    public boolean markMissedIfLate(Instant now) {
        if (status != TaskStatus.PENDING || !now.isAfter(toleranceUntil)) {
            return false;
        }
        this.status = TaskStatus.MISSED;
        this.missedAt = now;
        return true;
    }

    /** Concluída depois de marcada atrasada. */
    public boolean wasMissed() {
        return missedAt != null;
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

    public Instant getToleranceUntil() {
        return toleranceUntil;
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
