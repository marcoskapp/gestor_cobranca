package br.com.gestorcobranca.analytics.domain.model;

import java.math.BigDecimal;

/**
 * Indicador com denominador explícito: o painel mostra valor, numerador e denominador. Sem
 * denominador (zero ou sem fonte), {@code value} é nulo e {@code unavailableReason} diz por quê.
 *
 * @param value             numerador ÷ denominador (escala 4, HALF_EVEN); fração, não percentual
 * @param unavailableReason {@code no_denominator}, {@code no_source}, {@code not_segmentable},
 *                          {@code privacy_minimum} ou {@code out_of_scope}
 */
public record Ratio(BigDecimal value, BigDecimal numerator, BigDecimal denominator, String unavailableReason) {

    public static Ratio unavailable(String reason) {
        return new Ratio(null, null, null, reason);
    }
}
