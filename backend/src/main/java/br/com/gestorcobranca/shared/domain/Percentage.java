package br.com.gestorcobranca.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Percentual com 4 casas decimais (ex.: 2.5000 = 2,5%). Usado para descontos, juros, multa,
 * honorários e comissão.
 */
public record Percentage(BigDecimal value) implements Comparable<Percentage> {

    public static final int SCALE = 4;
    public static final Percentage ZERO = new Percentage(BigDecimal.ZERO);
    public static final Percentage HUNDRED = new Percentage(new BigDecimal("100"));

    public Percentage {
        Objects.requireNonNull(value, "value");
        value = value.setScale(SCALE, RoundingMode.HALF_EVEN);
        if (value.signum() < 0) {
            throw new IllegalArgumentException("Percentual não pode ser negativo: " + value);
        }
    }

    public static Percentage of(String value) {
        return new Percentage(new BigDecimal(value));
    }

    public static Percentage of(BigDecimal value) {
        return value == null ? ZERO : new Percentage(value);
    }

    /** Fração decimal (2,5% → 0.025) com precisão suficiente para cálculo de juros. */
    public BigDecimal asFraction() {
        return value.divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_EVEN);
    }

    public boolean exceeds(Percentage other) {
        return value.compareTo(other.value) > 0;
    }

    @Override
    public int compareTo(Percentage o) {
        return value.compareTo(o.value);
    }

    @Override
    public String toString() {
        return value.stripTrailingZeros().toPlainString() + "%";
    }
}
