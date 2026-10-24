package br.com.gestorcobranca.ingestion.domain.model.format;

import java.math.BigDecimal;

/**
 * Resultado da leitura de um valor monetário: o valor (escala 2, com sinal) ou o problema.
 *
 * @param style classe do texto original (para a análise por coluna e para apontar linhas suspeitas)
 */
public record AmountReading(BigDecimal value, AmountProblem problem, AmountStyle style) {

    public static AmountReading of(BigDecimal value, AmountStyle style) {
        return new AmountReading(value, null, style);
    }

    public static AmountReading failed(AmountProblem problem, AmountStyle style) {
        return new AmountReading(null, problem, style);
    }

    public boolean ok() {
        return value != null;
    }
}
