package br.com.gestorcobranca.ingestion.domain.model.review;

import br.com.gestorcobranca.shared.domain.Guard;

import java.util.Arrays;

/**
 * Estatística robusta de valores monetários em escala logarítmica ({@code importacao.md} §4.1):
 * {@code y = ln(valor)}, mediana {@code m} de {@code y} e {@code MAD = mediana(|y − m|)}. A mediana de quantidade par é
 * a média dos dois centrais — o mesmo que {@code percentile_cont(0.5)} do PostgreSQL, usado para o histórico.
 *
 * @param size      quantidade de valores (todos > 0)
 * @param medianLog mediana de {@code ln(valor)}
 * @param mad       mediana dos desvios absolutos de {@code ln(valor)}
 */
public record LogScaleStats(long size, double medianLog, double mad) {

    /** Fator que torna o MAD consistente com o desvio padrão de uma distribuição normal. */
    public static final double MAD_TO_SIGMA = 1.4826;

    public LogScaleStats {
        Guard.require(size >= 0 && mad >= 0 && Double.isFinite(medianLog) && Double.isFinite(mad), "stats.invalid",
                "Estatística inválida");
    }

    /** Calcula a partir de valores positivos (os demais são ignorados). */
    public static LogScaleStats of(double[] values) {
        double[] logs = Arrays.stream(values).filter(v -> v > 0 && Double.isFinite(v)).map(Math::log).toArray();
        if (logs.length == 0) {
            return new LogScaleStats(0, 0, 0);
        }
        double median = median(logs);
        double[] deviations = Arrays.stream(logs).map(y -> Math.abs(y - median)).toArray();
        return new LogScaleStats(logs.length, median, median(deviations));
    }

    /** Mediana (ordena uma cópia). */
    public static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    /** Valor típico (mediana na escala original). */
    public double median() {
        return Math.exp(medianLog);
    }

    /** Desvio robusto em escala logarítmica, com piso para distribuições muito concentradas. */
    public double sigma(double floor) {
        return Math.max(MAD_TO_SIGMA * mad, floor);
    }

    /** Escore robusto de um valor positivo. */
    public double z(double value, double floor) {
        return (Math.log(value) - medianLog) / sigma(floor);
    }

    /** Limite inferior da faixa típica ({@code e^(m − k·σ)}). */
    public double low(double k, double floor) {
        return Math.exp(medianLog - k * sigma(floor));
    }

    /** Limite superior da faixa típica ({@code e^(m + k·σ)}). */
    public double high(double k, double floor) {
        return Math.exp(medianLog + k * sigma(floor));
    }
}
