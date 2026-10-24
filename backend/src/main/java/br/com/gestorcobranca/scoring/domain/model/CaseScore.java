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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Score vigente do caso (um por caso): valor, faixa, probabilidade, modelo, features e contribuições
 * do último cálculo (explicação) e o último valor publicado aos consumidores.
 */
@Entity
@Table(name = "case_score", schema = "scoring")
public class CaseScore extends TenantScopedEntity {

    public enum Trigger { BATCH, ON_DEMAND, EVENT, REVIEW }

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "creditor_id", nullable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", nullable = false)
    private UUID portfolioId;

    @Column(name = "model_id", nullable = false)
    private UUID modelId;

    @Column(name = "model_version", nullable = false)
    private int modelVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_kind", nullable = false, length = 30)
    private ModelKind modelKind;

    @Column(name = "score", nullable = false)
    private int score;

    @Column(name = "band", nullable = false, length = 1)
    private String band;

    @Column(name = "probability", nullable = false, precision = 9, scale = 6)
    private BigDecimal probability;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "features", nullable = false)
    private String features;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contributions", nullable = false)
    private String contributions;

    @Column(name = "reference_date", nullable = false)
    private LocalDate referenceDate;

    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "calculation_trigger", nullable = false, length = 20)
    private Trigger trigger;

    @Column(name = "published_score")
    private Integer publishedScore;

    @Column(name = "published_band", length = 1)
    private String publishedBand;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected CaseScore() {
    }

    public CaseScore(UUID caseId, UUID debtorId) {
        Guard.require(caseId != null && debtorId != null, "scoring.case_required", "Caso obrigatório");
        this.caseId = caseId;
        this.debtorId = debtorId;
    }

    /** Resultado de um cálculo (não publica: ver {@link #shouldPublish} e {@link #markPublished}). */
    public void record(UUID creditorId, UUID portfolioId, ScoringModel model, Evaluation evaluation, String featuresJson,
                       String contributionsJson, LocalDate referenceDate, Instant calculatedAt, Trigger trigger) {
        this.creditorId = creditorId;
        this.portfolioId = portfolioId;
        this.modelId = model.getId();
        this.modelVersion = model.getModelVersion();
        this.modelKind = model.getKind();
        this.score = evaluation.score();
        this.band = model.bands().band(this.score);
        this.probability = BigDecimal.valueOf(evaluation.probability()).setScale(6, RoundingMode.HALF_EVEN);
        this.features = featuresJson;
        this.contributions = contributionsJson;
        this.referenceDate = referenceDate;
        this.calculatedAt = calculatedAt;
        this.trigger = trigger;
    }

    /** Publica no primeiro cálculo, na mudança de faixa ou com variação ≥ {@code minDelta} pontos. */
    public boolean shouldPublish(int minDelta) {
        return publishedScore == null || !band.equals(publishedBand) || Math.abs(score - publishedScore) >= minDelta;
    }

    public void markPublished(Instant now) {
        this.publishedScore = score;
        this.publishedBand = band;
        this.publishedAt = now;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public UUID getModelId() {
        return modelId;
    }

    public int getModelVersion() {
        return modelVersion;
    }

    public ModelKind getModelKind() {
        return modelKind;
    }

    public int getScore() {
        return score;
    }

    public String getBand() {
        return band;
    }

    public BigDecimal getProbability() {
        return probability;
    }

    public String getFeatures() {
        return features;
    }

    public String getContributions() {
        return contributions;
    }

    public LocalDate getReferenceDate() {
        return referenceDate;
    }

    public Instant getCalculatedAt() {
        return calculatedAt;
    }

    public Trigger getTrigger() {
        return trigger;
    }

    public Integer getPublishedScore() {
        return publishedScore;
    }

    public String getPublishedBand() {
        return publishedBand;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
