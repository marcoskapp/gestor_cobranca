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
 * Versão de modelo de score. Definição, dados de treino, métricas e cortes de faixa são imutáveis
 * depois de criados (colunas não atualizáveis e gatilho no banco); só o ciclo de vida muda.
 */
@Entity
@Table(name = "model", schema = "scoring")
public class ScoringModel extends TenantScopedEntity {

    @Column(name = "model_version", nullable = false, updatable = false)
    private int modelVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 30)
    private ModelKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ModelStatus status;

    @Column(name = "description", length = 200, updatable = false)
    private String description;

    @Column(name = "outcome_days", nullable = false, updatable = false)
    private int outcomeDays;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "definition", nullable = false, updatable = false)
    private String definition;

    @Column(name = "band_a", nullable = false, updatable = false)
    private int bandA;

    @Column(name = "band_b", nullable = false, updatable = false)
    private int bandB;

    @Column(name = "band_c", nullable = false, updatable = false)
    private int bandC;

    @Column(name = "band_d", nullable = false, updatable = false)
    private int bandD;

    @Column(name = "paid_share", nullable = false, updatable = false, precision = 5, scale = 4)
    private BigDecimal paidShare;

    @Column(name = "training_from", updatable = false)
    private LocalDate trainingFrom;

    @Column(name = "training_to", updatable = false)
    private LocalDate trainingTo;

    @Column(name = "validation_from", updatable = false)
    private LocalDate validationFrom;

    @Column(name = "validation_to", updatable = false)
    private LocalDate validationTo;

    @Column(name = "training_rows", nullable = false, updatable = false)
    private int trainingRows;

    @Column(name = "training_positives", nullable = false, updatable = false)
    private int trainingPositives;

    @Column(name = "validation_rows", nullable = false, updatable = false)
    private int validationRows;

    @Column(name = "validation_positives", nullable = false, updatable = false)
    private int validationPositives;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics", updatable = false)
    private String metrics;

    @Column(name = "failure_reasons", length = 500, updatable = false)
    private String failureReasons;

    /** Distribuição de referência para o PSI (no treino, ou a primeira população escorada como campeão). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reference_distribution")
    private String referenceDistribution;

    @Column(name = "trained_by", updatable = false)
    private UUID trainedBy;

    @Column(name = "trained_at", nullable = false, updatable = false)
    private Instant trainedAt;

    @Column(name = "promoted_at")
    private Instant promotedAt;

    @Column(name = "promoted_by")
    private UUID promotedBy;

    @Column(name = "ended_at")
    private Instant endedAt;

    protected ScoringModel() {
    }

    /**
     * @param trainedBy usuário que pediu o treino ou criou o scorecard (null = sistema)
     */
    public ScoringModel(int modelVersion, ModelKind kind, ModelStatus initialStatus, String description, int outcomeDays,
                        String definition, BandCutoffs bands, BigDecimal paidShare, TrainingSummary training,
                        String metrics, String failureReasons, String referenceDistribution, UUID trainedBy,
                        Instant trainedAt) {
        Guard.require(modelVersion > 0, "scoring.model.version_invalid", "Versão inválida");
        Guard.require(kind != null && definition != null && bands != null && paidShare != null,
                "scoring.model.definition_required", "Definição do modelo obrigatória");
        Guard.require(initialStatus == ModelStatus.CHALLENGER || initialStatus == ModelStatus.CHAMPION
                        || initialStatus == ModelStatus.FAILED_VALIDATION, "scoring.model.status_invalid",
                "Situação inicial inválida");
        Guard.require(description == null || description.length() <= 200, "scoring.model.description_too_long",
                "Descrição com até 200 caracteres");
        this.modelVersion = modelVersion;
        this.kind = kind;
        this.status = initialStatus;
        this.description = description;
        this.outcomeDays = outcomeDays;
        this.definition = definition;
        this.bandA = bands.a();
        this.bandB = bands.b();
        this.bandC = bands.c();
        this.bandD = bands.d();
        this.paidShare = paidShare.setScale(4, RoundingMode.HALF_EVEN);
        TrainingSummary t = training == null ? TrainingSummary.NONE : training;
        this.trainingFrom = t.trainingFrom();
        this.trainingTo = t.trainingTo();
        this.validationFrom = t.validationFrom();
        this.validationTo = t.validationTo();
        this.trainingRows = t.trainingRows();
        this.trainingPositives = t.trainingPositives();
        this.validationRows = t.validationRows();
        this.validationPositives = t.validationPositives();
        this.metrics = metrics;
        this.failureReasons = failureReasons == null || failureReasons.length() <= 500 ? failureReasons
                : failureReasons.substring(0, 500);
        this.referenceDistribution = referenceDistribution;
        this.trainedBy = trainedBy;
        this.trainedAt = trainedAt;
        if (initialStatus == ModelStatus.CHAMPION) {
            this.promotedAt = trainedAt;
            this.promotedBy = trainedBy;
        }
    }

    public record TrainingSummary(LocalDate trainingFrom, LocalDate trainingTo, LocalDate validationFrom,
                                  LocalDate validationTo, int trainingRows, int trainingPositives, int validationRows,
                                  int validationPositives) {

        public static final TrainingSummary NONE = new TrainingSummary(null, null, null, null, 0, 0, 0, 0);
    }

    /** Promoção manual do desafiante a campeão. */
    public void promote(UUID actorId, Instant now) {
        Guard.requireState(status == ModelStatus.CHALLENGER, "scoring.model.not_challenger",
                "Só o desafiante pode ser promovido");
        this.status = ModelStatus.CHAMPION;
        this.promotedAt = now;
        this.promotedBy = actorId;
    }

    /** Campeão substituído por uma promoção. */
    public void retire(Instant now) {
        Guard.requireState(status == ModelStatus.CHAMPION, "scoring.model.not_champion", "Modelo não é o campeão");
        this.status = ModelStatus.RETIRED;
        this.endedAt = now;
    }

    public void reject(Instant now) {
        Guard.requireState(status == ModelStatus.CHALLENGER, "scoring.model.not_challenger",
                "Só o desafiante pode ser rejeitado");
        this.status = ModelStatus.REJECTED;
        this.endedAt = now;
    }

    /** Desafiante substituído por outro mais novo antes de uma decisão. */
    public void supersede(Instant now) {
        Guard.requireState(status == ModelStatus.CHALLENGER, "scoring.model.not_challenger", "Modelo não é o desafiante");
        this.status = ModelStatus.SUPERSEDED;
        this.endedAt = now;
    }

    /** Fixa a distribuição de referência do PSI uma única vez. */
    public boolean defineReferenceDistribution(String json) {
        if (referenceDistribution != null) {
            return false;
        }
        this.referenceDistribution = json;
        return true;
    }

    public BandCutoffs bands() {
        return new BandCutoffs(bandA, bandB, bandC, bandD);
    }

    public int getModelVersion() {
        return modelVersion;
    }

    public ModelKind getKind() {
        return kind;
    }

    public ModelStatus getStatus() {
        return status;
    }

    public String getDescription() {
        return description;
    }

    public int getOutcomeDays() {
        return outcomeDays;
    }

    public String getDefinition() {
        return definition;
    }

    public BigDecimal getPaidShare() {
        return paidShare;
    }

    public LocalDate getTrainingFrom() {
        return trainingFrom;
    }

    public LocalDate getTrainingTo() {
        return trainingTo;
    }

    public LocalDate getValidationFrom() {
        return validationFrom;
    }

    public LocalDate getValidationTo() {
        return validationTo;
    }

    public int getTrainingRows() {
        return trainingRows;
    }

    public int getTrainingPositives() {
        return trainingPositives;
    }

    public int getValidationRows() {
        return validationRows;
    }

    public int getValidationPositives() {
        return validationPositives;
    }

    public String getMetrics() {
        return metrics;
    }

    public String getFailureReasons() {
        return failureReasons;
    }

    public String getReferenceDistribution() {
        return referenceDistribution;
    }

    public UUID getTrainedBy() {
        return trainedBy;
    }

    public Instant getTrainedAt() {
        return trainedAt;
    }

    public Instant getPromotedAt() {
        return promotedAt;
    }

    public UUID getPromotedBy() {
        return promotedBy;
    }

    public Instant getEndedAt() {
        return endedAt;
    }
}
