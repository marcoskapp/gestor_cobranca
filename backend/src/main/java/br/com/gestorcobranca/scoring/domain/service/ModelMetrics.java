package br.com.gestorcobranca.scoring.domain.service;

import br.com.gestorcobranca.scoring.domain.model.BandCutoffs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Métricas de discriminação e calibração (funções puras): AUC (Mann-Whitney, empates com posto
 * médio), Gini, KS, Brier, calibração por faixa e lift por decil. Probabilidades em [0, 1] e
 * rótulos 0/1 (1 = pagamento confirmado na janela).
 */
public final class ModelMetrics {

    private ModelMetrics() {
    }

    /**
     * Área sob a curva ROC = P(score de um positivo &gt; score de um negativo), empates contando ½.
     *
     * @return null se não houver positivos e negativos
     */
    public static Double auc(double[] scores, int[] labels) {
        int n = scores.length;
        Integer[] idx = order(scores);
        double[] ranks = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && scores[idx[j + 1]] == scores[idx[i]]) {
                j++;
            }
            double avg = (i + j) / 2.0 + 1.0;
            for (int k = i; k <= j; k++) {
                ranks[idx[k]] = avg;
            }
            i = j + 1;
        }
        long pos = 0;
        double rankSum = 0;
        for (int k = 0; k < n; k++) {
            if (labels[k] == 1) {
                pos++;
                rankSum += ranks[k];
            }
        }
        long neg = n - pos;
        if (pos == 0 || neg == 0) {
            return null;
        }
        return (rankSum - pos * (pos + 1) / 2.0) / ((double) pos * neg);
    }

    public static Double gini(Double auc) {
        return auc == null ? null : 2 * auc - 1;
    }

    /**
     * Kolmogorov-Smirnov: maior distância entre as distribuições acumuladas do score de positivos e
     * negativos (limiares nos valores distintos do score).
     */
    public static Double ks(double[] scores, int[] labels) {
        int n = scores.length;
        long pos = Arrays.stream(labels).filter(l -> l == 1).count();
        long neg = n - pos;
        if (pos == 0 || neg == 0) {
            return null;
        }
        Integer[] idx = order(scores);
        double cumPos = 0;
        double cumNeg = 0;
        double best = 0;
        int i = 0;
        while (i < n) {
            int j = i;
            while (j < n && scores[idx[j]] == scores[idx[i]]) {
                if (labels[idx[j]] == 1) {
                    cumPos++;
                } else {
                    cumNeg++;
                }
                j++;
            }
            best = Math.max(best, Math.abs(cumPos / pos - cumNeg / neg));
            i = j;
        }
        return best;
    }

    /** Erro quadrático médio da probabilidade (Brier). */
    public static Double brier(double[] probabilities, int[] labels) {
        if (probabilities.length == 0) {
            return null;
        }
        double s = 0;
        for (int i = 0; i < probabilities.length; i++) {
            double d = probabilities[i] - labels[i];
            s += d * d;
        }
        return s / probabilities.length;
    }

    /** Calibração de uma faixa: probabilidade média prevista × taxa observada. */
    public record BandCalibration(String band, long cases, long positives, double meanPredicted, Double observedRate) {
    }

    public static List<BandCalibration> calibration(double[] probabilities, int[] labels, BandCutoffs cutoffs) {
        List<BandCalibration> out = new ArrayList<>();
        for (String band : BandCutoffs.BANDS) {
            long cases = 0;
            long positives = 0;
            double sum = 0;
            for (int i = 0; i < probabilities.length; i++) {
                if (cutoffs.band(score(probabilities[i])).equals(band)) {
                    cases++;
                    positives += labels[i];
                    sum += probabilities[i];
                }
            }
            out.add(new BandCalibration(band, cases, positives, cases == 0 ? 0 : sum / cases,
                    cases == 0 ? null : (double) positives / cases));
        }
        return out;
    }

    /**
     * Decil de score (1 = maiores probabilidades): taxa observada, lift sobre a taxa geral e captura
     * acumulada dos positivos.
     */
    public record Decile(int decile, long cases, long positives, double rate, double lift, double cumulativeCapture) {
    }

    public static List<Decile> liftByDecile(double[] probabilities, int[] labels) {
        int n = probabilities.length;
        long totalPositives = Arrays.stream(labels).filter(l -> l == 1).count();
        List<Decile> out = new ArrayList<>();
        if (n == 0 || totalPositives == 0) {
            return out;
        }
        Integer[] idx = order(probabilities);
        // ordem decrescente: o maior primeiro
        Integer[] desc = new Integer[n];
        for (int i = 0; i < n; i++) {
            desc[i] = idx[n - 1 - i];
        }
        double overall = (double) totalPositives / n;
        long captured = 0;
        for (int d = 0; d < 10; d++) {
            int from = (int) Math.floor(d * n / 10.0);
            int to = (int) Math.floor((d + 1) * n / 10.0);
            if (to <= from) {
                continue;
            }
            long positives = 0;
            for (int k = from; k < to; k++) {
                positives += labels[desc[k]];
            }
            captured += positives;
            double rate = (double) positives / (to - from);
            out.add(new Decile(d + 1, to - from, positives, rate, rate / overall, (double) captured / totalPositives));
        }
        return out;
    }

    /** Score 0–1000 de uma probabilidade (mesma regra de {@code Evaluation#score}). */
    public static int score(double probability) {
        return (int) Math.round(Math.min(1.0, Math.max(0.0, probability)) * 1000.0);
    }

    /** Índices em ordem crescente de valor (estável). */
    private static Integer[] order(double[] values) {
        Integer[] idx = new Integer[values.length];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, Comparator.comparingDouble(i -> values[i]));
        return idx;
    }
}
