package br.com.gestorcobranca.incentive.domain.model.quality;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Formulário configurável de monitoria de qualidade. Os critérios são imutáveis (avaliações
 * antigas continuam reproduzíveis): para mudar, desativa-se o formulário e cria-se outro.
 */
@Entity
@Table(name = "quality_form", schema = "incentive")
public class QualityForm extends TenantScopedEntity {

    private static final BigDecimal TEN = BigDecimal.TEN;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "quality_form_criterion", schema = "incentive", joinColumns = @JoinColumn(name = "form_id"))
    @OrderColumn(name = "position")
    private List<QualityCriterion> criteria = new ArrayList<>();

    protected QualityForm() {
    }

    public QualityForm(String name, String description, List<QualityCriterion> criteria) {
        Guard.require(name != null && !name.isBlank() && name.trim().length() <= 120, "quality_form.name_invalid",
                "Nome obrigatório (até 120 caracteres)");
        Guard.require(description == null || description.length() <= 1000, "quality_form.description_too_long",
                "Descrição até 1000 caracteres");
        Guard.require(criteria != null && !criteria.isEmpty() && criteria.size() <= 50, "quality_form.criteria_required",
                "Informe de 1 a 50 critérios");
        Set<String> codes = new HashSet<>();
        criteria.forEach(c -> Guard.require(codes.add(c.getCode()), "quality_form.criterion_duplicate",
                "Critério repetido: " + c.getCode()));
        this.name = name.trim();
        this.description = description;
        this.active = true;
        this.criteria.addAll(criteria);
    }

    public void deactivate() {
        this.active = false;
    }

    /** Resultado de uma avaliação: nota final 0–10 (2 casas) e indicação de falha crítica. */
    public record Score(BigDecimal score, boolean criticalFailure) {
    }

    /**
     * Nota = média ponderada das notas por critério (0–10), com duas casas (HALF_EVEN). Falha
     * crítica (nota zero em critério crítico) zera a avaliação. Todos os critérios devem ser
     * avaliados, e nenhum critério estranho ao formulário é aceito.
     */
    public Score score(Map<String, BigDecimal> scores) {
        Guard.requireState(active, "quality_form.inactive", "Formulário desativado");
        Guard.require(scores != null && scores.keySet().equals(codes()), "quality_evaluation.scores_incomplete",
                "Avalie exatamente os critérios do formulário");
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal weights = BigDecimal.ZERO;
        boolean criticalFailure = false;
        for (QualityCriterion criterion : criteria) {
            BigDecimal value = scores.get(criterion.getCode());
            Guard.require(value != null && value.signum() >= 0 && value.compareTo(TEN) <= 0 && value.scale() <= 2,
                    "quality_evaluation.score_invalid", "Nota do critério deve estar entre 0 e 10");
            weighted = weighted.add(value.multiply(criterion.getWeight()));
            weights = weights.add(criterion.getWeight());
            criticalFailure |= criterion.isCritical() && value.signum() == 0;
        }
        BigDecimal total = criticalFailure ? BigDecimal.ZERO.setScale(2)
                : weighted.divide(weights, 2, RoundingMode.HALF_EVEN);
        return new Score(total, criticalFailure);
    }

    private Set<String> codes() {
        Set<String> codes = new HashSet<>();
        criteria.forEach(c -> codes.add(c.getCode()));
        return codes;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }

    public List<QualityCriterion> getCriteria() {
        return Collections.unmodifiableList(criteria);
    }
}
