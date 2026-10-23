package br.com.gestorcobranca.incentive.domain.model.quality;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Avaliação de monitoria (append-only): nota 0–10 calculada pelo formulário. Correções são feitas
 * com nova avaliação (a média do período usa todas). Usada no portão de qualidade (§4.2).
 */
@Entity
@Table(name = "quality_evaluation", schema = "incentive")
public class QualityEvaluation extends TenantScopedEntity {

    @Column(name = "form_id", nullable = false, updatable = false)
    private UUID formId;

    @Column(name = "evaluated_user_id", nullable = false, updatable = false)
    private UUID evaluatedUserId;

    @Column(name = "evaluator_id", nullable = false, updatable = false)
    private UUID evaluatorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, updatable = false, length = 20)
    private EvaluationReferenceType referenceType;

    @Column(name = "reference_id", updatable = false)
    private UUID referenceId;

    @Column(name = "evaluated_on", nullable = false, updatable = false)
    private LocalDate evaluatedOn;

    @Column(name = "score", nullable = false, updatable = false, precision = 5, scale = 2)
    private BigDecimal score;

    @Column(name = "critical_failure", nullable = false, updatable = false)
    private boolean criticalFailure;

    @Column(name = "comments", updatable = false, length = 4000)
    private String comments;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "quality_evaluation_score", schema = "incentive",
            joinColumns = @JoinColumn(name = "evaluation_id"))
    @MapKeyColumn(name = "criterion_code", length = 40)
    @Column(name = "score", nullable = false, precision = 5, scale = 2)
    private Map<String, BigDecimal> criterionScores = new HashMap<>();

    protected QualityEvaluation() {
    }

    public QualityEvaluation(QualityForm form, UUID evaluatedUserId, UUID evaluatorId, EvaluationReferenceType referenceType,
                             UUID referenceId, LocalDate evaluatedOn, Map<String, BigDecimal> scores, String comments,
                             LocalDate today) {
        Guard.require(evaluatedUserId != null && evaluatorId != null, "quality_evaluation.users_required",
                "Avaliado e avaliador obrigatórios");
        Guard.require(!evaluatedUserId.equals(evaluatorId), "quality_evaluation.self_evaluation",
                "O avaliador não pode avaliar a si mesmo");
        Guard.require(referenceType != null, "quality_evaluation.reference_required", "Tipo de referência obrigatório");
        Guard.require(evaluatedOn != null && !evaluatedOn.isAfter(today), "quality_evaluation.date_invalid",
                "Data da monitoria não pode ser futura");
        Guard.require(comments == null || comments.length() <= 4000, "quality_evaluation.comments_too_long",
                "Comentários até 4000 caracteres");
        QualityForm.Score result = form.score(scores);
        this.formId = form.getId();
        this.evaluatedUserId = evaluatedUserId;
        this.evaluatorId = evaluatorId;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.evaluatedOn = evaluatedOn;
        this.score = result.score();
        this.criticalFailure = result.criticalFailure();
        this.comments = comments;
        this.criterionScores.putAll(scores);
    }

    public UUID getFormId() {
        return formId;
    }

    public UUID getEvaluatedUserId() {
        return evaluatedUserId;
    }

    public UUID getEvaluatorId() {
        return evaluatorId;
    }

    public EvaluationReferenceType getReferenceType() {
        return referenceType;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public LocalDate getEvaluatedOn() {
        return evaluatedOn;
    }

    public BigDecimal getScore() {
        return score;
    }

    public boolean isCriticalFailure() {
        return criticalFailure;
    }

    public String getComments() {
        return comments;
    }

    public Map<String, BigDecimal> getCriterionScores() {
        return Collections.unmodifiableMap(criterionScores);
    }
}
