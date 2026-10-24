package br.com.gestorcobranca.ingestion.domain.model.review;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Totais calculados do arquivo já convertido: todas as linhas de dados (inclusive as com erro), cada coluna lida com a
 * interpretação aplicada.
 *
 * @param sums    soma por medida monetária
 * @param invalid valores que não puderam ser lidos (não entram na soma), por medida monetária
 */
public record ComputedTotals(long rows, Map<TotalsMeasure, BigDecimal> sums, Map<TotalsMeasure, Long> invalid) {

    public ComputedTotals {
        sums = Map.copyOf(sums);
        invalid = Map.copyOf(invalid);
    }

    public BigDecimal value(TotalsMeasure measure) {
        return measure == TotalsMeasure.ROWS ? BigDecimal.valueOf(rows) : sums.getOrDefault(measure, BigDecimal.ZERO);
    }

    public long invalid(TotalsMeasure measure) {
        return invalid.getOrDefault(measure, 0L);
    }
}
