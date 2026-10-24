package br.com.gestorcobranca.scoring.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro de uma decisão de governança sobre o desafiante (append-only no banco): promoção a
 * campeão ou rejeição, com justificativa e a comparação campeão × desafiante vista por quem decidiu.
 */
@Entity
@Table(name = "model_decision", schema = "scoring")
public class ModelDecision extends TenantScopedEntity {

    public enum Decision { PROMOTE, REJECT }

    @Column(name = "model_id", nullable = false, updatable = false)
    private UUID modelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, updatable = false, length = 20)
    private Decision decision;

    @Column(name = "previous_champion_id", updatable = false)
    private UUID previousChampionId;

    @Column(name = "justification", nullable = false, updatable = false, length = 1000)
    private String justification;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "comparison", nullable = false, updatable = false)
    private String comparison;

    @Column(name = "decided_by", nullable = false, updatable = false)
    private UUID decidedBy;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt;

    protected ModelDecision() {
    }

    public ModelDecision(UUID modelId, Decision decision, UUID previousChampionId, String justification,
                         String comparison, UUID decidedBy, Instant decidedAt) {
        String text = justification == null ? "" : justification.trim();
        Guard.require(text.length() >= 10 && text.length() <= 1000, "scoring.decision.justification_required",
                "Justificativa obrigatória (10 a 1000 caracteres)");
        Guard.require(modelId != null && decision != null && decidedBy != null && comparison != null,
                "scoring.decision.invalid", "Decisão incompleta");
        this.modelId = modelId;
        this.decision = decision;
        this.previousChampionId = previousChampionId;
        this.justification = text;
        this.comparison = comparison;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
    }

    public UUID getModelId() {
        return modelId;
    }

    public Decision getDecision() {
        return decision;
    }

    public UUID getPreviousChampionId() {
        return previousChampionId;
    }

    public String getJustification() {
        return justification;
    }

    public String getComparison() {
        return comparison;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
