package br.com.gestorcobranca.ingestion.domain.service.review;

import br.com.gestorcobranca.ingestion.domain.model.review.ComputedTotals;
import br.com.gestorcobranca.ingestion.domain.model.review.ControlTotalsInput;
import br.com.gestorcobranca.ingestion.domain.model.review.MeasureComparison;
import br.com.gestorcobranca.ingestion.domain.model.review.TotalsComparison;
import br.com.gestorcobranca.ingestion.domain.model.review.TotalsMeasure;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Conferência dos totais de controle ({@code importacao.md} §3): diferença dentro da tolerância e, na divergência, a
 * razão próxima de 10ⁿ que denuncia um separador decimal mal interpretado. Função pura.
 *
 * @param countTolerance  diferença aceita na quantidade de linhas
 * @param amountTolerance diferença aceita nas somas (arredondamento)
 * @param scaleTolerance  desvio relativo aceito em torno de 10ⁿ
 */
public record ControlTotals(long countTolerance, BigDecimal amountTolerance, double scaleTolerance) {

    public TotalsComparison compare(ControlTotalsInput input, ComputedTotals computed) {
        List<MeasureComparison> result = new ArrayList<>();
        for (Map.Entry<TotalsMeasure, BigDecimal> entry : input.entered().entrySet()) {
            TotalsMeasure measure = entry.getKey();
            BigDecimal entered = entry.getValue();
            BigDecimal calculated = computed.value(measure);
            BigDecimal difference = entered.subtract(calculated);
            BigDecimal tolerance = measure == TotalsMeasure.ROWS ? BigDecimal.valueOf(countTolerance) : amountTolerance;
            boolean matches = difference.abs().compareTo(tolerance) <= 0;
            Integer exponent = null;
            if (!matches && measure != TotalsMeasure.ROWS) {
                OptionalInt n = ScaleRatio.exponent(entered, calculated, scaleTolerance);
                exponent = n.isPresent() ? n.getAsInt() : null;
            }
            result.add(new MeasureComparison(measure, entered, calculated, difference, matches, exponent));
        }
        return new TotalsComparison(result);
    }
}
