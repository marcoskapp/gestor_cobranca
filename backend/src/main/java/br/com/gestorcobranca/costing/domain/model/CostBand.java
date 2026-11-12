package br.com.gestorcobranca.costing.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Faixa de custo de pessoas (desenho §3.1, opção A aprovada): custo-empresa por hora (salário + encargos + benefícios
 * ÷ horas contratadas) informado pela gestão, por cargo — nunca o valor de uma pessoa. Um nome pode ter várias
 * vigências sem sobreposição; a pessoa aponta para o nome ({@link UserCostBand}).
 */
@Entity
@Table(name = "cost_band", schema = "costing")
public class CostBand extends Dated {

    public static final int NAME_MAX = 80;
    public static final BigDecimal MAX_HOURLY_COST = new BigDecimal("9999999999.99");

    @Column(name = "name", nullable = false, length = NAME_MAX, updatable = false)
    private String name;

    @Column(name = "hourly_cost", nullable = false, updatable = false)
    private BigDecimal hourlyCost;

    protected CostBand() {
    }

    public CostBand(String name, BigDecimal hourlyCost, LocalDate validFrom) {
        super(validFrom);
        this.name = normalizeName(name);
        Guard.require(hourlyCost != null && hourlyCost.signum() > 0 && hourlyCost.compareTo(MAX_HOURLY_COST) <= 0,
                "costing.band_cost_invalid", "Informe um custo por hora maior que zero");
        Guard.require(hourlyCost.stripTrailingZeros().scale() <= 2, "costing.band_cost_scale",
                "O custo por hora tem no máximo duas casas decimais");
        this.hourlyCost = hourlyCost.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Nome sem espaços nas pontas e com espaços internos simples (a faixa é identificada pelo nome). */
    public static String normalizeName(String name) {
        Guard.require(name != null && !name.isBlank(), "costing.band_name_required", "Informe o nome da faixa");
        String normalized = name.trim().replaceAll("\\s+", " ");
        Guard.require(normalized.length() <= NAME_MAX, "costing.band_name_too_long",
                "Nome da faixa com no máximo " + NAME_MAX + " caracteres");
        return normalized;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getHourlyCost() {
        return hourlyCost;
    }
}
