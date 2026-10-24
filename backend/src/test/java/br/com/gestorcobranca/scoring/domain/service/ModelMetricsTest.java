package br.com.gestorcobranca.scoring.domain.service;

import br.com.gestorcobranca.scoring.domain.model.BandCutoffs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Valores conferidos à mão (exemplos clássicos de livro-texto). */
class ModelMetricsTest {

    private static final double[] SCORES = {0.1, 0.4, 0.35, 0.8};
    private static final int[] LABELS = {0, 0, 1, 1};

    @Test
    void aucIsTheProbabilityThatAPositiveOutranksANegative() {
        // pares (positivo, negativo): (0.35,0.1)✓ (0.35,0.4)✗ (0.8,0.1)✓ (0.8,0.4)✓ → 3/4
        assertThat(ModelMetrics.auc(SCORES, LABELS)).isCloseTo(0.75, within(1e-12));
        assertThat(ModelMetrics.gini(0.75)).isCloseTo(0.5, within(1e-12));
    }

    @Test
    void tiesCountHalf() {
        assertThat(ModelMetrics.auc(new double[]{0.5, 0.5}, new int[]{0, 1})).isCloseTo(0.5, within(1e-12));
        // 2 positivos e 2 negativos, um empate entre classes: (1 + 1 + 0.5 + 1) / 4
        assertThat(ModelMetrics.auc(new double[]{0.2, 0.6, 0.6, 0.9}, new int[]{0, 0, 1, 1}))
                .isCloseTo(0.875, within(1e-12));
    }

    @Test
    void aucAndKsNeedBothClasses() {
        assertThat(ModelMetrics.auc(new double[]{0.1, 0.2}, new int[]{1, 1})).isNull();
        assertThat(ModelMetrics.ks(new double[]{0.1, 0.2}, new int[]{0, 0})).isNull();
    }

    @Test
    void ksIsTheLargestGapBetweenCumulativeDistributions() {
        // limiares: 0.1 → |0 − 0.5| = 0.5; 0.35 → |0.5 − 0.5| = 0; 0.4 → |0.5 − 1| = 0.5; 0.8 → 0
        assertThat(ModelMetrics.ks(SCORES, LABELS)).isCloseTo(0.5, within(1e-12));
        assertThat(ModelMetrics.ks(new double[]{0.1, 0.2, 0.8, 0.9}, new int[]{0, 0, 1, 1}))
                .isCloseTo(1.0, within(1e-12));
    }

    @Test
    void brierIsTheMeanSquaredError() {
        // (0.01 + 0.16 + 0.4225 + 0.04) / 4
        assertThat(ModelMetrics.brier(SCORES, LABELS)).isCloseTo(0.158125, within(1e-12));
    }

    @Test
    void calibrationGroupsByBand() {
        double[] p = {0.50, 0.45, 0.30, 0.10, 0.02};
        int[] y = {1, 0, 1, 0, 0};
        List<ModelMetrics.BandCalibration> bands = ModelMetrics.calibration(p, y, BandCutoffs.DEFAULT);
        assertThat(bands).extracting(ModelMetrics.BandCalibration::band).containsExactly("A", "B", "C", "D", "E");
        assertThat(bands.get(0).cases()).isEqualTo(2);
        assertThat(bands.get(0).meanPredicted()).isCloseTo(0.475, within(1e-12));
        assertThat(bands.get(0).observedRate()).isCloseTo(0.5, within(1e-12));
        assertThat(bands.get(1).cases()).isEqualTo(1);
        assertThat(bands.get(2).cases()).isZero();
        assertThat(bands.get(2).observedRate()).isNull();
        assertThat(bands.get(3).cases()).isEqualTo(1);
        assertThat(bands.get(4).cases()).isEqualTo(1);
    }

    @Test
    void liftByDecileOrdersFromHighestProbability() {
        double[] p = new double[20];
        int[] y = new int[20];
        for (int i = 0; i < 20; i++) {
            p[i] = i / 20.0;
            y[i] = i >= 16 ? 1 : 0;           // os 4 maiores são positivos
        }
        List<ModelMetrics.Decile> deciles = ModelMetrics.liftByDecile(p, y);
        assertThat(deciles).hasSize(10);
        assertThat(deciles.get(0).positives()).isEqualTo(2);
        assertThat(deciles.get(0).rate()).isCloseTo(1.0, within(1e-12));
        assertThat(deciles.get(0).lift()).isCloseTo(5.0, within(1e-12));     // 1,0 ÷ 0,2
        assertThat(deciles.get(1).cumulativeCapture()).isCloseTo(1.0, within(1e-12));
        assertThat(deciles.get(9).positives()).isZero();
    }
}
