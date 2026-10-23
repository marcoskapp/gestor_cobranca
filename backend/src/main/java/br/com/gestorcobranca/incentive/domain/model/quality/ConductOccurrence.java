package br.com.gestorcobranca.incentive.domain.model.quality;

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
 * Ocorrência de conduta confirmada (violação de compliance, reclamação, contestação procedente).
 * Pode ser dispensada com justificativa (resultado de contestação), nunca apagada.
 */
@Entity
@Table(name = "conduct_occurrence", schema = "incentive")
public class ConductOccurrence extends TenantScopedEntity {

    public enum Status { CONFIRMED, DISMISSED }

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 40)
    private OccurrenceType type;

    @Column(name = "occurred_on", nullable = false, updatable = false)
    private LocalDate occurredOn;

    @Column(name = "description", nullable = false, updatable = false, length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", updatable = false, length = 20)
    private EvaluationReferenceType referenceType;

    @Column(name = "reference_id", updatable = false)
    private UUID referenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "registered_by", nullable = false, updatable = false)
    private UUID registeredBy;

    @Column(name = "dismissed_by")
    private UUID dismissedBy;

    @Column(name = "dismissed_at")
    private Instant dismissedAt;

    @Column(name = "dismissal_reason", length = 2000)
    private String dismissalReason;

    protected ConductOccurrence() {
    }

    public ConductOccurrence(UUID userId, OccurrenceType type, LocalDate occurredOn, String description,
                             EvaluationReferenceType referenceType, UUID referenceId, UUID registeredBy, LocalDate today) {
        Guard.require(userId != null && type != null, "conduct.user_and_type_required", "Negociador e tipo obrigatórios");
        Guard.require(!userId.equals(registeredBy), "conduct.self_registration", "Não é possível registrar ocorrência própria");
        Guard.require(occurredOn != null && !occurredOn.isAfter(today), "conduct.date_invalid", "Data não pode ser futura");
        Guard.require(description != null && description.trim().length() >= 10 && description.length() <= 2000,
                "conduct.description_invalid", "Descrição obrigatória (10 a 2000 caracteres)");
        this.userId = userId;
        this.type = type;
        this.occurredOn = occurredOn;
        this.description = description.trim();
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.status = Status.CONFIRMED;
        this.registeredBy = registeredBy;
    }

    public void dismiss(String reason, UUID actorId, Instant now) {
        Guard.requireState(status == Status.CONFIRMED, "conduct.already_dismissed", "Ocorrência já dispensada");
        Guard.require(reason != null && reason.trim().length() >= 10 && reason.length() <= 2000, "conduct.reason_invalid",
                "Justificativa obrigatória (10 a 2000 caracteres)");
        Guard.require(!actorId.equals(registeredBy), "conduct.same_actor", "Quem registrou não pode dispensar a ocorrência");
        Guard.require(!actorId.equals(userId), "conduct.self_dismissal", "O negociador não pode dispensar a própria ocorrência");
        this.status = Status.DISMISSED;
        this.dismissedBy = actorId;
        this.dismissedAt = now;
        this.dismissalReason = reason.trim();
    }

    public UUID getUserId() {
        return userId;
    }

    public OccurrenceType getType() {
        return type;
    }

    public LocalDate getOccurredOn() {
        return occurredOn;
    }

    public String getDescription() {
        return description;
    }

    public EvaluationReferenceType getReferenceType() {
        return referenceType;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getRegisteredBy() {
        return registeredBy;
    }

    public UUID getDismissedBy() {
        return dismissedBy;
    }

    public Instant getDismissedAt() {
        return dismissedAt;
    }

    public String getDismissalReason() {
        return dismissalReason;
    }
}
