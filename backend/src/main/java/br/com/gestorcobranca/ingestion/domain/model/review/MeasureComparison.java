package br.com.gestorcobranca.ingestion.domain.model.review;

import java.math.BigDecimal;

/**
 * Comparação de um total.
 *
 * @param difference    informado − calculado
 * @param scaleExponent {@code n} quando informado ÷ calculado ≈ 10ⁿ (provável separador decimal); nulo caso contrário
 */
public record MeasureComparison(TotalsMeasure measure, BigDecimal entered, BigDecimal computed, BigDecimal difference,
                                boolean matches, Integer scaleExponent) {
}
