package br.com.gestorcobranca.analytics.domain.service;

import br.com.gestorcobranca.analytics.domain.model.Ratio;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fórmulas puras dos indicadores (docs/negocio/indicadores.md). Contrato com a operação: mudar
 * uma fórmula exige atualizar o documento e os testes com o resultado conferido à mão.
 */
public final class Indicators {

    public static final int RATIO_SCALE = 4;

    private Indicators() {
    }

    /** numerador ÷ denominador; denominador zero ou nulo → indisponível ({@code no_denominator}). */
    public static Ratio ratio(BigDecimal numerator, BigDecimal denominator) {
        BigDecimal n = numerator == null ? BigDecimal.ZERO : numerator;
        if (denominator == null || denominator.signum() == 0) {
            return new Ratio(null, n, denominator == null ? BigDecimal.ZERO : denominator, "no_denominator");
        }
        return new Ratio(n.divide(denominator, RATIO_SCALE, RoundingMode.HALF_EVEN), n, denominator, null);
    }

    public static Ratio ratio(long numerator, long denominator) {
        return ratio(BigDecimal.valueOf(numerator), BigDecimal.valueOf(denominator));
    }

    /** Valor recuperado líquido = recebido − estornado (nunca o valor negociado). */
    public static BigDecimal net(BigDecimal gross, BigDecimal reversed) {
        return nz(gross).subtract(nz(reversed));
    }

    /** Horas com 2 casas a partir de segundos. */
    public static BigDecimal hours(long seconds) {
        return BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(3600), 2, RoundingMode.HALF_EVEN);
    }

    /** Taxa por hora (ex.: acordos por hora logada): quantidade ÷ (segundos ÷ 3600). */
    public static Ratio perHour(long count, long seconds) {
        if (seconds <= 0) {
            return new Ratio(null, BigDecimal.valueOf(count), BigDecimal.ZERO, "no_denominator");
        }
        BigDecimal h = BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(3600), 6, RoundingMode.HALF_EVEN);
        return new Ratio(BigDecimal.valueOf(count).divide(h, RATIO_SCALE, RoundingMode.HALF_EVEN),
                BigDecimal.valueOf(count), hours(seconds), null);
    }

    public static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
