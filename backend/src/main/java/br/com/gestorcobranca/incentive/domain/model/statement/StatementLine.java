package br.com.gestorcobranca.incentive.domain.model.statement;

import br.com.gestorcobranca.shared.domain.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/**
 * Linha da memória de cálculo do extrato (item contestável). A soma de {@code amount} de todas
 * as linhas é o valor calculado.
 *
 * @see br.com.gestorcobranca.incentive.domain.service.CalculationResult.Line
 */
@Embeddable
public class StatementLine {

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "component", nullable = false, length = 30)
    private String component;

    @Column(name = "description", nullable = false, length = 300)
    private String description;

    @Column(name = "segment_key", length = 200)
    private String segmentKey;

    @Column(name = "base_amount", precision = 19, scale = 2)
    private Money baseAmount;

    @Column(name = "rate", precision = 19, scale = 6)
    private BigDecimal rate;

    @Column(name = "factor", precision = 19, scale = 6)
    private BigDecimal factor;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private Money amount;

    protected StatementLine() {
    }

    public StatementLine(int lineNumber, String component, String description, String segmentKey, Money baseAmount,
                         BigDecimal rate, BigDecimal factor, Money amount) {
        this.lineNumber = lineNumber;
        this.component = component;
        this.description = description.length() <= 300 ? description : description.substring(0, 300);
        this.segmentKey = segmentKey;
        this.baseAmount = baseAmount;
        this.rate = rate;
        this.factor = factor;
        this.amount = amount;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getComponent() {
        return component;
    }

    public String getDescription() {
        return description;
    }

    public String getSegmentKey() {
        return segmentKey;
    }

    public Money getBaseAmount() {
        return baseAmount;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public BigDecimal getFactor() {
        return factor;
    }

    public Money getAmount() {
        return amount;
    }
}
