package br.com.gestorcobranca.incentive.domain.model.quality;

import br.com.gestorcobranca.shared.domain.Guard;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/**
 * Critério do formulário de monitoria. {@code critical}: nota zero neste critério zera a
 * avaliação (falha crítica, ex.: ameaça ou exposição do devedor).
 */
@Embeddable
public class QualityCriterion {

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "description", nullable = false, length = 300)
    private String description;

    @Column(name = "weight", nullable = false, precision = 9, scale = 4)
    private BigDecimal weight;

    @Column(name = "critical", nullable = false)
    private boolean critical;

    protected QualityCriterion() {
    }

    public QualityCriterion(String code, String description, BigDecimal weight, boolean critical) {
        Guard.require(code != null && code.matches("[A-Z0-9_]{2,40}"), "quality_form.criterion_code_invalid",
                "Código do critério: 2 a 40 caracteres em maiúsculas, dígitos ou _");
        Guard.require(description != null && !description.isBlank() && description.length() <= 300,
                "quality_form.criterion_description_invalid", "Descrição do critério obrigatória (até 300 caracteres)");
        Guard.require(weight != null && weight.signum() > 0 && weight.compareTo(new BigDecimal("100")) <= 0,
                "quality_form.criterion_weight_invalid", "Peso do critério entre 0 e 100");
        this.code = code;
        this.description = description.trim();
        this.weight = weight;
        this.critical = critical;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public boolean isCritical() {
        return critical;
    }
}
