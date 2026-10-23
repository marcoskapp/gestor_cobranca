package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Período de dono do atendimento do caso, com vigência {@code [validFrom, validTo)}. Cada
 * mudança de dono encerra o período vigente e abre um novo, de modo que "quem era o responsável
 * no instante T" é uma consulta direta (modelo de crédito por carteira, tempo em fila,
 * produtividade, rastreio de redistribuições). Somente o fim de vigência pode ser gravado, uma
 * única vez — o banco recusa qualquer outra alteração.
 */
@Entity
@Table(name = "case_assignment", schema = "collection")
public class CaseAssignment extends TenantScopedEntity {

    private static final int REASON_MAX = 500;

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

    @Column(name = "reason", length = REASON_MAX, updatable = false)
    private String reason;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    protected CaseAssignment() {
    }

    /**
     * Abre o período com o dono atual do caso.
     *
     * @param previous período que está sendo encerrado (nulo na abertura do caso)
     */
    public CaseAssignment(CollectionCase c, AssignmentMode mode, CaseAssignment previous, String reason,
                          Instant validFrom) {
        Guard.require(mode != null, "assignment.mode_required", "Origem da atribuição obrigatória");
        Guard.require(validFrom != null, "assignment.start_required", "Início da vigência obrigatório");
        this.caseId = c.getId();
        this.creditorId = c.getCreditorId();
        this.mode = mode;
        this.previousOwnerType = previous == null ? OwnerType.NONE : previous.getOwnerType();
        this.previousUserId = previous == null ? null : previous.getUserId();
        this.ownerType = c.getOwnerType();
        this.userId = c.getAssigneeUserId();
        this.teamId = c.getAssigneeTeamId();
        this.queueId = c.getQueueId();
        String clean = reason == null || reason.isBlank() ? null : reason.trim();
        this.reason = clean != null && clean.length() > REASON_MAX ? clean.substring(0, REASON_MAX) : clean;
        this.validFrom = validFrom;
    }

    /** Encerra a vigência (idempotente: um período já encerrado não muda). */
    public void close(Instant at) {
        if (validTo != null) {
            return;
        }
        this.validTo = at.isBefore(validFrom) ? validFrom : at;
    }

    public boolean isCurrent() {
        return validTo == null;
    }

    /** Duração do período (vigente: até {@code now}). */
    public Duration duration(Instant now) {
        return Duration.between(validFrom, validTo == null ? now : validTo);
    }

    /** O mesmo dono (tipo, operador, equipe e fila) do caso, isto é, nada mudou. */
    public boolean sameOwnerAs(CollectionCase c) {
        return ownerType == c.getOwnerType() && java.util.Objects.equals(userId, c.getAssigneeUserId())
                && java.util.Objects.equals(teamId, c.getAssigneeTeamId())
                && java.util.Objects.equals(queueId, c.getQueueId());
    }

    public Optional<Instant> validTo() {
        return Optional.ofNullable(validTo);
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

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }
}
