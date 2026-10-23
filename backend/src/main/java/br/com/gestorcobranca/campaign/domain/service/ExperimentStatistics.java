package br.com.gestorcobranca.campaign.domain.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Estatística dos testes A/B (F25), com intervalo de confiança de 95%:
 * <ul>
 *   <li><b>Taxas</b> (contato efetivo, resposta, acordo, pagamento): intervalo de Wilson por braço;
 *       diferença em relação ao controle (ou à primeira variante, sem controle) pelo método híbrido de
 *       Newcombe (score de Wilson) — bom comportamento com taxas pequenas e amostras moderadas.</li>
 *   <li><b>Valor recuperado por devedor</b>: média com intervalo normal ({@code média ± 1,96·s/√n});
 *       diferença pela aproximação de Welch ({@code ± 1,96·√(s₁²/n₁ + s₀²/n₀)}).</li>
 * </ul>
 * Avisos de amostra insuficiente: braço abaixo da amostra mínima do experimento, ou menos de 5
 * sucessos ou 5 fracassos (a aproximação normal deixa de valer). Os cálculos usam {@code double}:
 * são estimativas estatísticas, não valores financeiros (os totais vêm em {@code BigDecimal}).
 */
public final class ExperimentStatistics {

    /** Quantil 97,5% da normal padrão. */
    public static final double Z = 1.959963984540054;
    public static final String SAMPLE_BELOW_MINIMUM = "SAMPLE_BELOW_MINIMUM";
    public static final String FEW_EVENTS = "FEW_EVENTS";
    public static final String NO_VARIANCE = "NO_VARIANCE";

    private ExperimentStatistics() {
    }

    public record Interval(double estimate, double lower, double upper) {

        /** O intervalo da diferença não contém zero. */
        public boolean significant() {
            return lower > 0 || upper < 0;
        }
    }

    public record Proportion(long successes, long n, Interval interval) {
    }

    public record Mean(long n, double sum, double standardDeviation, Interval interval) {
    }

    /** Intervalo de Wilson para x sucessos em n. Com n = 0, estimativa 0 e intervalo [0, 1]. */
    public static Proportion proportion(long successes, long n) {
        if (n <= 0) {
            return new Proportion(0, 0, new Interval(0, 0, 1));
        }
        double p = (double) successes / n;
        double z2 = Z * Z;
        double denominator = 1 + z2 / n;
        double center = (p + z2 / (2.0 * n)) / denominator;
        double half = Z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denominator;
        return new Proportion(successes, n, new Interval(p, Math.max(0, center - half), Math.min(1, center + half)));
    }

    /** Diferença {@code a − b} (Newcombe, híbrido de Wilson). */
    public static Interval difference(Proportion a, Proportion b) {
        double pa = a.interval().estimate();
        double pb = b.interval().estimate();
        double d = pa - pb;
        double lower = d - Math.sqrt(sq(pa - a.interval().lower()) + sq(b.interval().upper() - pb));
        double upper = d + Math.sqrt(sq(a.interval().upper() - pa) + sq(pb - b.interval().lower()));
        return new Interval(d, lower, upper);
    }

    /** Média a partir de n, soma e soma dos quadrados (desvio padrão amostral). */
    public static Mean mean(long n, double sum, double sumOfSquares) {
        if (n <= 0) {
            return new Mean(0, 0, 0, new Interval(0, 0, 0));
        }
        double mean = sum / n;
        double variance = n > 1 ? Math.max(0, (sumOfSquares - n * mean * mean) / (n - 1)) : 0;
        double sd = Math.sqrt(variance);
        double half = Z * sd / Math.sqrt(n);
        return new Mean(n, sum, sd, new Interval(mean, mean - half, mean + half));
    }

    /** Diferença de médias {@code a − b} (Welch, aproximação normal). */
    public static Interval difference(Mean a, Mean b) {
        double d = a.interval().estimate() - b.interval().estimate();
        if (a.n() == 0 || b.n() == 0) {
            return new Interval(d, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        }
        double half = Z * Math.sqrt(sq(a.standardDeviation()) / a.n() + sq(b.standardDeviation()) / b.n());
        return new Interval(d, d - half, d + half);
    }

    /** Avisos de amostra de um braço para uma taxa. */
    public static List<String> warnings(Proportion p, int minSample) {
        List<String> warnings = new ArrayList<>();
        if (p.n() < minSample) {
            warnings.add(SAMPLE_BELOW_MINIMUM);
        }
        if (p.successes() < 5 || p.n() - p.successes() < 5) {
            warnings.add(FEW_EVENTS);
        }
        return warnings;
    }

    /** Avisos de amostra de um braço para a média. */
    public static List<String> warnings(Mean m, int minSample) {
        List<String> warnings = new ArrayList<>();
        if (m.n() < minSample) {
            warnings.add(SAMPLE_BELOW_MINIMUM);
        }
        if (m.n() > 0 && m.standardDeviation() == 0) {
            warnings.add(NO_VARIANCE);
        }
        return warnings;
    }

    private static double sq(double v) {
        return v * v;
    }
}
