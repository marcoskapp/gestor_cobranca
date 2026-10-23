package br.com.gestorcobranca.legal.domain.model;

import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Prazo jurídico com vencimento calculado pelo {@code DeadlineCalculator} (dias úteis, corridos
 * ou data fixa), responsável, alertas escalonados (último nível de dias úteis já alertado) e
 * cumprimento. O vencimento de prazos pendentes é recalculado quando o calendário muda (feriado
 * ou suspensão forense cadastrados depois).
 */
@Entity
@Table(name = "legal_deadline", schema = "legal")
public class LegalDeadline extends TenantScopedEntity {

    @Column(name = "matter_id", nullable = false, updatable = false)
    private UUID matterId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "lawsuit_id", updatable = false)
    private UUID lawsuitId;

    @Column(name = "movement_id", updatable = false)
    private UUID movementId;

    @Column(name = "measure_id", updatable = false)
    private UUID measureId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30, updatable = false)
    private DeadlineKind kind;

    @Column(name = "description", nullable = false, length = 300)
    private String description;

    @Column(name = "court_code", length = 5, updatable = false)
    private String courtCode;

    @Column(name = "start_date", updatable = false)
    private LocalDate startDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "start_event", length = 20, updatable = false)
    private StartEvent startEvent;

    @Column(name = "term_days", updatable = false)
    private Integer termDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "counting_mode", nullable = false, length = 20, updatable = false)
    private CountingMode countingMode;

    @Column(name = "fixed_date", updatable = false)
    private LocalDate fixedDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "responsible_user_id")
    private UUID responsibleUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DeadlineStatus status;

    @Column(name = "completed_on")
    private LocalDate completedOn;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "last_alert_level")
    private Integer lastAlertLevel;

    @Column(name = "last_alert_at")
    private Instant lastAlertAt;

    protected LegalDeadline() {
    }

    public LegalDeadline(UUID matterId, UUID creditorId, UUID lawsuitId, UUID movementId, UUID measureId,
                         DeadlineKind kind, String description, String courtCode, LocalDate startDate,
                         StartEvent startEvent, Integer termDays, CountingMode mode, LocalDate fixedDate,
                         LocalDate dueDate, UUID responsibleUserId) {
        Guard.require(kind != null, "legal_deadline.kind_required", "Natureza do prazo obrigatória");
        Guard.require(mode != null, "legal_deadline.mode_required", "Forma de contagem obrigatória");
        Guard.require(mode == CountingMode.FIXED_DATE || (startDate != null && termDays != null),
                "legal_deadline.start_required", "Informe a data de início e a quantidade de dias");
        Guard.require(dueDate != null, "legal_deadline.due_date_required", "Vencimento obrigatório");
        this.matterId = matterId;
        this.creditorId = creditorId;
        this.lawsuitId = lawsuitId;
        this.movementId = movementId;
        this.measureId = measureId;
        this.kind = kind;
        this.description = description(description);
        this.courtCode = courtCode;
        this.startDate = mode == CountingMode.FIXED_DATE ? null : startDate;
        this.startEvent = mode == CountingMode.FIXED_DATE ? null : (startEvent == null ? StartEvent.INTIMATION : startEvent);
        this.termDays = mode == CountingMode.FIXED_DATE ? null : termDays;
        this.countingMode = mode;
        this.fixedDate = mode == CountingMode.FIXED_DATE ? fixedDate : null;
        this.dueDate = dueDate;
        this.responsibleUserId = responsibleUserId;
        this.status = DeadlineStatus.PENDING;
    }

    /** Novo vencimento após mudança do calendário (somente pendentes). @return true se mudou */
    public boolean reschedule(LocalDate newDue) {
        if (status != DeadlineStatus.PENDING || newDue == null || newDue.equals(dueDate)) {
            return false;
        }
        this.dueDate = newDue;
        this.lastAlertLevel = null;
        this.lastAlertAt = null;
        return true;
    }

    /**
     * Cumprimento. Um prazo marcado como perdido pela varredura pode ser corrigido para cumprido
     * se o cumprimento ocorreu até o vencimento (lançamento tardio).
     */
    public DeadlineStatus complete(LocalDate on, LocalDate today, String note) {
        Guard.require(on != null && !on.isAfter(today), "legal_deadline.date_invalid",
                "Data do cumprimento obrigatória e não futura");
        if (status == DeadlineStatus.MISSED) {
            Guard.requireState(!on.isAfter(dueDate), "legal_deadline.missed",
                    "Prazo perdido: só é possível registrar cumprimento ocorrido até o vencimento");
        } else if (status != DeadlineStatus.PENDING) {
            throw new ConflictException("legal_deadline.not_pending", "Prazo já encerrado");
        }
        DeadlineStatus previous = status;
        this.status = DeadlineStatus.MET;
        this.completedOn = on;
        this.statusReason = note == null || note.isBlank() ? null : trim(note);
        return previous;
    }

    public void cancel(String reason) {
        Guard.requireState(status == DeadlineStatus.PENDING, "legal_deadline.not_pending", "Prazo já encerrado");
        Guard.require(reason != null && !reason.isBlank(), "legal_deadline.reason_required", "Motivo obrigatório");
        this.status = DeadlineStatus.CANCELLED;
        this.statusReason = trim(reason);
    }

    /** Vencido sem cumprimento (varredura diária). */
    public boolean markMissed(LocalDate today) {
        if (status != DeadlineStatus.PENDING || !dueDate.isBefore(today)) {
            return false;
        }
        this.status = DeadlineStatus.MISSED;
        return true;
    }

    /**
     * Registra o alerta de um nível (dias úteis restantes) uma única vez; níveis são decrescentes.
     *
     * @return true se o nível ainda não tinha sido alertado
     */
    public boolean alert(int level, Instant now) {
        if (status != DeadlineStatus.PENDING || (lastAlertLevel != null && lastAlertLevel <= level)) {
            return false;
        }
        this.lastAlertLevel = level;
        this.lastAlertAt = now;
        return true;
    }

    public void assignTo(UUID userId) {
        this.responsibleUserId = userId;
    }

    public boolean onTime() {
        return status == DeadlineStatus.MET && completedOn != null && !completedOn.isAfter(dueDate);
    }

    private static String description(String text) {
        Guard.require(text != null && !text.isBlank() && text.trim().length() <= 300, "legal_deadline.description_required",
                "Descrição do prazo obrigatória (até 300 caracteres)");
        return text.trim();
    }

    private static String trim(String text) {
        String v = text.trim();
        Guard.require(v.length() <= 500, "legal_deadline.reason_too_long", "Texto deve ter até 500 caracteres");
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

    public UUID getMovementId() {
        return movementId;
    }

    public UUID getMeasureId() {
        return measureId;
    }

    public DeadlineKind getKind() {
        return kind;
    }

    public String getDescription() {
        return description;
    }

    public String getCourtCode() {
        return courtCode;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public StartEvent getStartEvent() {
        return startEvent;
    }

    public Integer getTermDays() {
        return termDays;
    }

    public CountingMode getCountingMode() {
        return countingMode;
    }

    public LocalDate getFixedDate() {
        return fixedDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public UUID getResponsibleUserId() {
        return responsibleUserId;
    }

    public DeadlineStatus getStatus() {
        return status;
    }

    public LocalDate getCompletedOn() {
        return completedOn;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public Integer getLastAlertLevel() {
        return lastAlertLevel;
    }

    public Instant getLastAlertAt() {
        return lastAlertAt;
    }
}
