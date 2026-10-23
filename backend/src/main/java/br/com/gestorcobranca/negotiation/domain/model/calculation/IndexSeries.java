package br.com.gestorcobranca.negotiation.domain.model.calculation;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Série de variações mensais de um índice (% no mês, com sinal), entregue ao cálculo pela porta
 * {@code MonetaryIndexProvider}. Valor puro, sem acesso a banco.
 *
 * @param code  código do índice
 * @param rates variação por competência
 */
public record IndexSeries(String code, Map<YearMonth, BigDecimal> rates) {

    public IndexSeries {
        Objects.requireNonNull(code, "code");
        rates = rates == null ? Map.of() : Map.copyOf(new TreeMap<>(rates));
    }

    public Optional<BigDecimal> rate(YearMonth competence) {
        return Optional.ofNullable(rates.get(competence));
    }

    /** Última competência publicada (vazio se a série não tiver taxas). */
    public Optional<YearMonth> lastPublished() {
        return rates.keySet().stream().max(YearMonth::compareTo);
    }
}
