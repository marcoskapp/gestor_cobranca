package br.com.gestorcobranca.ingestion.domain.model.review;

import java.util.List;
import java.util.Optional;

/** Resultado da conferência dos totais. */
public record TotalsComparison(List<MeasureComparison> measures) {

    public TotalsComparison {
        measures = List.copyOf(measures);
    }

    public boolean matches() {
        return measures.stream().allMatch(MeasureComparison::matches);
    }

    public List<MeasureComparison> mismatched() {
        return measures.stream().filter(m -> !m.matches()).toList();
    }

    /** Primeira divergência com razão 10ⁿ. */
    public Optional<MeasureComparison> scaleHint() {
        return measures.stream().filter(m -> !m.matches() && m.scaleExponent() != null).findFirst();
    }
}
