package br.com.gestorcobranca.scoring.domain.service;

import br.com.gestorcobranca.scoring.domain.model.Feature;
import br.com.gestorcobranca.scoring.domain.model.FeatureVector;
import br.com.gestorcobranca.scoring.domain.model.LogisticDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Dados sintéticos com resposta conhecida: logit = −1 + 1,5·x₁ − 0,8·x₂ com x ~ N(0, 1) (features de
 * transformação identidade). O ajuste deve recuperar os coeficientes (na escala padronizada,
 * β·desvio ≈ β) e as probabilidades.
 */
class LogisticRegressionTrainerTest {

    private static final Feature X1 = Feature.RESPONSIVE_CHANNELS_90D;
    private static final Feature X2 = Feature.PORTFOLIO_RECOVERY_RATE;
    private static final Feature CONSTANT = Feature.ACTIVE_AGREEMENT;

    @Test
    void recoversKnownCoefficients() {
        Data data = synthetic(40_000, 42);
        LogisticRegressionTrainer.Fit fit = LogisticRegressionTrainer.train(data.rows, data.labels,
                List.of(X1, X2, CONSTANT), 1e-6);
        assertThat(fit.converged()).isTrue();
        assertThat(fit.iterations()).isLessThan(15);
        LogisticDefinition d = fit.definition();
        // feature sem variação fica fora do modelo
        assertThat(d.terms()).extracting(LogisticDefinition.Term::feature).containsExactly(X1, X2);
        LogisticDefinition.Term t1 = d.terms().get(0);
        LogisticDefinition.Term t2 = d.terms().get(1);
        assertThat(t1.coefficient() / t1.std()).isCloseTo(1.5, within(0.06));
        assertThat(t2.coefficient() / t2.std()).isCloseTo(-0.8, within(0.06));
        // intercepto na escala original: b0 − Σ βⱼ·médiaⱼ/desvioⱼ
        double intercept = d.intercept() - t1.coefficient() * t1.mean() / t1.std() - t2.coefficient() * t2.mean() / t2.std();
        assertThat(intercept).isCloseTo(-1.0, within(0.06));

        double p = d.evaluate(vector(1.0, 0.5, 0.0)).probability();
        double truth = 1 / (1 + Math.exp(-(-1 + 1.5 - 0.4)));
        assertThat(p).isCloseTo(truth, within(0.02));
        // AUC próxima da teórica do modelo verdadeiro e bem acima de 0,5
        double[] scores = data.rows.stream().mapToDouble(r -> d.evaluate(r).probability()).toArray();
        assertThat(ModelMetrics.auc(scores, data.labels)).isGreaterThan(0.80);
    }

    @Test
    void strongerRegularizationShrinksCoefficients() {
        Data data = synthetic(2_000, 7);
        double weak = Math.abs(LogisticRegressionTrainer.train(data.rows, data.labels, List.of(X1, X2), 1e-6)
                .definition().terms().getFirst().coefficient());
        double strong = Math.abs(LogisticRegressionTrainer.train(data.rows, data.labels, List.of(X1, X2), 2_000)
                .definition().terms().getFirst().coefficient());
        assertThat(strong).isLessThan(weak * 0.6);
    }

    @Test
    void contributionsAddUpToTheLogit() {
        Data data = synthetic(5_000, 11);
        LogisticDefinition d = LogisticRegressionTrainer.train(data.rows, data.labels, List.of(X1, X2), 1.0).definition();
        var e = d.evaluate(vector(2.0, -1.0, 0.0));
        double sum = d.intercept() + e.contributions().stream().mapToDouble(c -> c.logOdds()).sum();
        assertThat(e.logit()).isCloseTo(sum, within(1e-12));
        assertThat(e.topFactors(1).getFirst().feature()).isEqualTo(X1);
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> LogisticRegressionTrainer.train(List.of(), new int[0], List.of(X1), 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        Data data = synthetic(10, 1);
        assertThatThrownBy(() -> LogisticRegressionTrainer.train(data.rows, data.labels, List.of(CONSTANT), 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private record Data(List<FeatureVector> rows, int[] labels) {
    }

    private static Data synthetic(int n, long seed) {
        Random random = new Random(seed);
        List<FeatureVector> rows = new ArrayList<>(n);
        int[] labels = new int[n];
        for (int i = 0; i < n; i++) {
            double x1 = random.nextGaussian();
            double x2 = random.nextGaussian();
            double p = 1 / (1 + Math.exp(-(-1 + 1.5 * x1 - 0.8 * x2)));
            labels[i] = random.nextDouble() < p ? 1 : 0;
            rows.add(vector(x1, x2, 0.0));
        }
        return new Data(rows, labels);
    }

    private static FeatureVector vector(double x1, double x2, double constant) {
        Map<Feature, Double> v = new EnumMap<>(Feature.class);
        v.put(X1, x1);
        v.put(X2, x2);
        v.put(CONSTANT, constant);
        return FeatureVector.of(v);
    }
}
