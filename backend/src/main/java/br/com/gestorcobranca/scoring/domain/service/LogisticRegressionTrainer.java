package br.com.gestorcobranca.scoring.domain.service;

import br.com.gestorcobranca.scoring.domain.model.Feature;
import br.com.gestorcobranca.scoring.domain.model.FeatureVector;
import br.com.gestorcobranca.scoring.domain.model.LogisticDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Regressão logística com regularização L2 (ridge), ajustada por Newton-Raphson / IRLS — o método
 * padrão para GLMs: converge em poucas iterações para p pequeno (≈ 20 features) e tem solução única
 * com λ &gt; 0. Implementação própria em Java, sem dependência externa (decisão em
 * {@code docs/tecnico/desenho/scoring.md}): ~150 linhas verificadas por testes com dados sintéticos de
 * coeficientes conhecidos.
 * <p>
 * As features são transformadas ({@link Feature.Transform}) e padronizadas (média 0, desvio 1) com
 * estatísticas do treino; a penalidade {@code λ‖β‖²/2} não se aplica ao intercepto. Features sem
 * variação no treino ficam fora do modelo (coeficiente indefinido).
 */
public final class LogisticRegressionTrainer {

    private static final int MAX_ITERATIONS = 100;
    private static final double TOLERANCE = 1e-8;
    private static final double MIN_STD = 1e-9;

    private LogisticRegressionTrainer() {
    }

    /** Resultado do ajuste. */
    public record Fit(LogisticDefinition definition, int iterations, boolean converged, double logLikelihood) {
    }

    /**
     * @param lambda força da regularização L2 (≥ 1e-6)
     */
    public static Fit train(List<FeatureVector> rows, int[] labels, List<Feature> features, double lambda) {
        if (rows.size() != labels.length || rows.isEmpty()) {
            throw new IllegalArgumentException("Linhas e rótulos com tamanhos diferentes ou vazios");
        }
        if (!(lambda >= 1e-6)) {
            throw new IllegalArgumentException("lambda deve ser >= 1e-6");
        }
        int n = rows.size();
        // estatísticas da transformação (média ignora ausentes, que são imputados pela média)
        List<Feature> used = new ArrayList<>();
        List<double[]> stats = new ArrayList<>();
        for (Feature f : features) {
            double sum = 0;
            double sumSq = 0;
            int count = 0;
            for (FeatureVector r : rows) {
                Double t = f.transformed(r.get(f));
                if (t != null) {
                    sum += t;
                    sumSq += t * t;
                    count++;
                }
            }
            if (count == 0) {
                continue;
            }
            double mean = sum / count;
            double variance = Math.max(0, sumSq / count - mean * mean);
            double std = Math.sqrt(variance);
            if (std < MIN_STD) {
                continue;
            }
            used.add(f);
            stats.add(new double[]{mean, std});
        }
        int p = used.size() + 1;
        double[][] x = new double[n][p];
        for (int i = 0; i < n; i++) {
            x[i][0] = 1.0;
            for (int j = 0; j < used.size(); j++) {
                Double t = used.get(j).transformed(rows.get(i).get(used.get(j)));
                x[i][j + 1] = t == null ? 0.0 : (t - stats.get(j)[0]) / stats.get(j)[1];
            }
        }
        double[] beta = new double[p];
        double positives = 0;
        for (int y : labels) {
            positives += y;
        }
        double base = Math.min(1 - 1e-6, Math.max(1e-6, positives / n));
        beta[0] = Math.log(base / (1 - base));
        double previous = logLikelihood(x, labels, beta, lambda);
        boolean converged = false;
        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;
            double[][] h = new double[p][p];
            double[] g = new double[p];
            for (int i = 0; i < n; i++) {
                double mu = sigmoid(dot(x[i], beta));
                double w = Math.max(mu * (1 - mu), 1e-12);
                double r = labels[i] - mu;
                for (int a = 0; a < p; a++) {
                    g[a] += x[i][a] * r;
                    double wa = w * x[i][a];
                    for (int b = a; b < p; b++) {
                        h[a][b] += wa * x[i][b];
                    }
                }
            }
            for (int a = 0; a < p; a++) {
                for (int b = 0; b < a; b++) {
                    h[a][b] = h[b][a];
                }
                if (a > 0) {
                    h[a][a] += lambda;
                    g[a] -= lambda * beta[a];
                }
            }
            double[] step = Cholesky.solve(h, g);
            // passo de Newton com redução se a verossimilhança penalizada piorar
            double scale = 1.0;
            double[] candidate = new double[p];
            double current;
            do {
                for (int a = 0; a < p; a++) {
                    candidate[a] = beta[a] + scale * step[a];
                }
                current = logLikelihood(x, labels, candidate, lambda);
                scale /= 2;
            } while (current < previous - 1e-12 && scale > 1e-6);
            double maxDelta = 0;
            for (int a = 0; a < p; a++) {
                maxDelta = Math.max(maxDelta, Math.abs(candidate[a] - beta[a]));
            }
            System.arraycopy(candidate, 0, beta, 0, p);
            previous = current;
            if (maxDelta < TOLERANCE) {
                converged = true;
                break;
            }
        }
        List<LogisticDefinition.Term> terms = new ArrayList<>();
        for (int j = 0; j < used.size(); j++) {
            terms.add(new LogisticDefinition.Term(used.get(j), beta[j + 1], stats.get(j)[0], stats.get(j)[1]));
        }
        if (terms.isEmpty()) {
            throw new IllegalArgumentException("Nenhuma feature com variação no treino");
        }
        return new Fit(new LogisticDefinition(beta[0], terms), iteration, converged, previous);
    }

    /** Log-verossimilhança penalizada (sem penalidade no intercepto). */
    static double logLikelihood(double[][] x, int[] y, double[] beta, double lambda) {
        double ll = 0;
        for (int i = 0; i < x.length; i++) {
            double eta = dot(x[i], beta);
            // log(1 + e^eta) estável
            double softplus = eta > 0 ? eta + Math.log1p(Math.exp(-eta)) : Math.log1p(Math.exp(eta));
            ll += y[i] * eta - softplus;
        }
        double penalty = 0;
        for (int a = 1; a < beta.length; a++) {
            penalty += beta[a] * beta[a];
        }
        return ll - lambda * penalty / 2;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    private static double sigmoid(double v) {
        return v >= 0 ? 1.0 / (1.0 + Math.exp(-v)) : Math.exp(v) / (1.0 + Math.exp(v));
    }

    /** Decomposição de Cholesky para sistemas simétricos definidos positivos. */
    static final class Cholesky {

        private Cholesky() {
        }

        static double[] solve(double[][] a, double[] b) {
            int n = b.length;
            double[][] l = new double[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j <= i; j++) {
                    double sum = a[i][j];
                    for (int k = 0; k < j; k++) {
                        sum -= l[i][k] * l[j][k];
                    }
                    if (i == j) {
                        if (sum <= 0) {
                            throw new ArithmeticException("Matriz não é definida positiva");
                        }
                        l[i][i] = Math.sqrt(sum);
                    } else {
                        l[i][j] = sum / l[j][j];
                    }
                }
            }
            double[] y = new double[n];
            for (int i = 0; i < n; i++) {
                double sum = b[i];
                for (int k = 0; k < i; k++) {
                    sum -= l[i][k] * y[k];
                }
                y[i] = sum / l[i][i];
            }
            double[] x = new double[n];
            for (int i = n - 1; i >= 0; i--) {
                double sum = y[i];
                for (int k = i + 1; k < n; k++) {
                    sum -= l[k][i] * x[k];
                }
                x[i] = sum / l[i][i];
            }
            return x;
        }
    }
}
