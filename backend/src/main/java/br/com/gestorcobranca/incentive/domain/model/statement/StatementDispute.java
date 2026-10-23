package br.com.gestorcobranca.incentive.domain.model.statement;

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
 * Contestação de um item do extrato pelo negociador (§5.6): uma linha da memória de cálculo, um
 * lançamento do razão ou um acordo que o negociador entende faltar. Resolvida pelo gestor com
 * justificativa; quando aceita, normalmente acompanha um ajuste.
 */
@Entity
@Table(name = "statement_dispute", schema = "incentive")
public class StatementDispute extends TenantScopedEntity {

    @Column(name = "statement_id", nullable = false, updatable = false)
    private UUID statementId;

    @Column(name = "line_number", updatable = false)
    private Integer lineNumber;

    @Column(name = "ledger_entry_id", updatable = false)
    private UUID ledgerEntryId;

    @Column(name = "agreement_id", updatable = false)
    private UUID agreementId;

    @Column(name = "reason", nullable = false, updatable = false, length = 2000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DisputeStatus status;

    @Column(name = "opened_by", nullable = false, updatable = false)
    private UUID openedBy;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "resolution", length = 2000)
    private String resolution;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "adjustment_id")
    private UUID adjustmentId;

    protected StatementDispute() {
    }

    public StatementDispute(UUID statementId, Integer lineNumber, UUID ledgerEntryId, UUID agreementId, String reason,
                            UUID openedBy, Instant now) {
        Guard.require(lineNumber != null || ledgerEntryId != null || agreementId != null, "dispute.target_required",
                "Indique a linha, o lançamento ou o acordo contestado");
        Guard.require(reason != null && reason.trim().length() >= 10 && reason.length() <= 2000, "dispute.reason_invalid",
                "Justificativa obrigatória (10 a 2000 caracteres)");
        this.statementId = statementId;
        this.lineNumber = lineNumber;
        this.ledgerEntryId = ledgerEntryId;
        this.agreementId = agreementId;
        this.reason = reason.trim();
        this.status = DisputeStatus.OPEN;
        this.openedBy = openedBy;
        this.openedAt = now;
    }

    public void resolve(boolean accepted, String resolution, UUID adjustmentId, UUID actorId, Instant now) {
        Guard.requireState(status == DisputeStatus.OPEN, "dispute.not_open", "Contestação já resolvida");
        Guard.require(resolution != null && resolution.trim().length() >= 10 && resolution.length() <= 2000,
                "dispute.resolution_invalid", "Justificativa da resolução obrigatória (10 a 2000 caracteres)");
        Guard.require(!actorId.equals(openedBy), "dispute.self_resolution", "Quem contestou não pode resolver a contestação");
        this.status = accepted ? DisputeStatus.ACCEPTED : DisputeStatus.REJECTED;
        this.resolution = resolution.trim();
        this.resolvedBy = actorId;
        this.resolvedAt = now;
        this.adjustmentId = adjustmentId;
    }

    public boolean isOpen() {
        return status == DisputeStatus.OPEN;
    }

    public UUID getStatementId() {
        return statementId;
    }

    public Integer getLineNumber() {
        return lineNumber;
    }

    public UUID getLedgerEntryId() {
        return ledgerEntryId;
    }

    public UUID getAgreementId() {
        return agreementId;
    }

    public String getReason() {
        return reason;
    }

    public DisputeStatus getStatus() {
        return status;
    }

    public UUID getOpenedBy() {
        return openedBy;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public String getResolution() {
        return resolution;
    }

    public UUID getResolvedBy() {
        return resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public UUID getAdjustmentId() {
        return adjustmentId;
    }
}
