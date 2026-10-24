package br.com.gestorcobranca.scoring.domain.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Índice de estabilidade da população (PSI): {@code Σ (atualᵢ − esperadoᵢ) · ln(atualᵢ / esperadoᵢ)}
 * sobre faixas definidas na população de referência (decis, sem repetir cortes) mais uma faixa de
 * ausentes. Proporções zero recebem {@value #EPSILON} para o logaritmo. Leitura usual de mercado:
 * &lt; 0,10 estável; 0,10–0,25 atenção; ≥ 0,25 mudança relevante.
 */
public final class PopulationStability {

    static final double EPSILON = 1e-4;

    private PopulationStability() {
    }

    /**
     * Distribuição de referência: cortes superiores (exclusivos) das faixas, proporção de cada faixa
     * (última = acima do último corte) e proporção de ausentes.
     */
    public record Distribution(List<Double> cuts, List<Double> proportions, double missing, long size) {
    }

    /** Referência com até {@code bins} faixas por quantis (cortes repetidos são fundidos). */
    public static Distribution reference(List<Double> values, int bins) {
        double[] present = values.stream().filter(v -> v != null && Double.isFinite(v)).mapToDouble(Double::doubleValue)
                .sorted().toArray();
        long size = values.size();
        double missing = size == 0 ? 0 : (double) (size - present.length) / size;
        List<Double> cuts = new ArrayList<>();
        if (present.length > 0) {
            for (int b = 1; b < bins; b++) {
                double q = present[(int) Math.min(present.length - 1, Math.floor(b * present.length / (double) bins))];
                if (q > present[0] && (cuts.isEmpty() || q > cuts.getLast())) {
                    cuts.add(q);
                }
            }
        }
        return new Distribution(List.copyOf(cuts), proportions(values, cuts), missing, size);
    }

    /** Proporção de cada faixa (sobre o total, incluindo ausentes). */
    static List<Double> proportions(List<Double> values, List<Double> cuts) {
        double[] counts = new double[cuts.size() + 1];
        long total = values.size();
        for (Double v : values) {
            if (v == null || !Double.isFinite(v)) {
                continue;
            }
            counts[bin(v, cuts)]++;
        }
        List<Double> out = new ArrayList<>();
        for (double c : counts) {
            out.add(total == 0 ? 0 : c / total);
        }
        return out;
    }

    private static int bin(double v, List<Double> cuts) {
        int i = 0;
        while (i < cuts.size() && v >= cuts.get(i)) {
            i++;
        }
        return i;
    }

    /** PSI dos valores atuais contra a referência (null sem valores atuais). */
    public static Double psi(Distribution reference, List<Double> actual) {
        if (actual.isEmpty() || reference.size() == 0) {
            return null;
        }
        List<Double> current = proportions(actual, reference.cuts());
        double missingNow = (double) actual.stream().filter(v -> v == null || !Double.isFinite(v)).count() / actual.size();
        double psi = 0;
        for (int i = 0; i < current.size(); i++) {
            psi += term(current.get(i), reference.proportions().get(i));
        }
        if (reference.missing() > 0 || missingNow > 0) {
            psi += term(missingNow, reference.missing());
        }
        return psi;
    }

    /** PSI direto entre proporções esperadas e atuais (mesmas faixas). */
    public static double psi(double[] expected, double[] actual) {
        if (expected.length != actual.length) {
            throw new IllegalArgumentException("Faixas diferentes");
        }
        return Arrays.stream(range(expected.length)).mapToDouble(i -> term(actual[i], expected[i])).sum();
    }

    private static int[] range(int n) {
        int[] r = new int[n];
        for (int i = 0; i < n; i++) {
            r[i] = i;
        }
        return r;
    }

    private static double term(double actual, double expected) {
        double a = Math.max(actual, EPSILON);
        double e = Math.max(expected, EPSILON);
        return (a - e) * Math.log(a / e);
    }
}
