package br.com.gestorcobranca.scoring.domain.model;

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
 * Revisão de um score (LGPD art. 20 — direito de revisão): recálculo com os dados atuais, com o
 * score anterior e o novo, quem revisou e a origem do pedido. Append-only no banco; sem texto livre
 * (minimização).
 */
@Entity
@Table(name = "score_review", schema = "scoring")
public class ScoreReview extends TenantScopedEntity {

    public enum Origin {
        /** Pedido do titular (solicitação LGPD registrada no compliance; referência opcional). */
        DATA_SUBJECT_REQUEST,
        /** Pedido do credor ou da operação. */
        OPERATION_REQUEST,
        /** Verificação de qualidade interna. */
        QUALITY_CHECK
    }

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, updatable = false, length = 30)
    private Origin origin;

    @Column(name = "request_reference", updatable = false)
    private UUID requestReference;

    @Column(name = "previous_score", updatable = false)
    private Integer previousScore;

    @Column(name = "previous_band", length = 1, updatable = false)
    private String previousBand;

    @Column(name = "previous_model_id", updatable = false)
    private UUID previousModelId;

    @Column(name = "new_score", nullable = false, updatable = false)
    private int newScore;

    @Column(name = "new_band", nullable = false, length = 1, updatable = false)
    private String newBand;

    @Column(name = "new_model_id", nullable = false, updatable = false)
    private UUID newModelId;

    @Column(name = "reviewed_by", nullable = false, updatable = false)
    private UUID reviewedBy;

    @Column(name = "reviewed_at", nullable = false, updatable = false)
    private Instant reviewedAt;

    protected ScoreReview() {
    }

    public ScoreReview(UUID caseId, UUID creditorId, Origin origin, UUID requestReference, Integer previousScore,
                       String previousBand, UUID previousModelId, int newScore, String newBand, UUID newModelId,
                       UUID reviewedBy, Instant reviewedAt) {
        Guard.require(origin != null, "scoring.review.origin_required", "Origem da revisão obrigatória");
        this.caseId = caseId;
        this.creditorId = creditorId;
        this.origin = origin;
        this.requestReference = requestReference;
        this.previousScore = previousScore;
        this.previousBand = previousBand;
        this.previousModelId = previousModelId;
        this.newScore = newScore;
        this.newBand = newBand;
        this.newModelId = newModelId;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public Origin getOrigin() {
        return origin;
    }

    public UUID getRequestReference() {
        return requestReference;
    }

    public Integer getPreviousScore() {
        return previousScore;
    }

    public String getPreviousBand() {
        return previousBand;
    }

    public UUID getPreviousModelId() {
        return previousModelId;
    }

    public int getNewScore() {
        return newScore;
    }

    public String getNewBand() {
        return newBand;
    }

    public UUID getNewModelId() {
        return newModelId;
    }

    public UUID getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }
}
