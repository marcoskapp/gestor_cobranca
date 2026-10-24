package br.com.gestorcobranca.scoring.domain.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PopulationStabilityTest {

    @Test
    void psiMatchesHandComputedValue() {
        // (0.25 − 0.5)·ln(0.25/0.5) + (0.75 − 0.5)·ln(0.75/0.5) = 0.173287 + 0.101366
        double psi = PopulationStability.psi(new double[]{0.5, 0.5}, new double[]{0.25, 0.75});
        assertThat(psi).isCloseTo(0.274653, within(1e-6));
        assertThat(PopulationStability.psi(new double[]{0.2, 0.3, 0.5}, new double[]{0.2, 0.3, 0.5}))
                .isCloseTo(0.0, within(1e-12));
    }

    @Test
    void sameValuesAreStableAndShiftedValuesAreNot() {
        List<Double> base = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            base.add((double) (i % 100));
        }
        PopulationStability.Distribution ref = PopulationStability.reference(base, 10);
        assertThat(ref.cuts()).hasSize(9);
        assertThat(ref.proportions()).hasSize(10).allSatisfy(p -> assertThat(p).isCloseTo(0.1, within(1e-9)));
        assertThat(PopulationStability.psi(ref, base)).isCloseTo(0.0, within(1e-12));

        List<Double> shifted = base.stream().map(v -> v + 50).toList();
        assertThat(PopulationStability.psi(ref, shifted)).isGreaterThan(0.25);
    }

    @Test
    void missingValuesHaveTheirOwnBucket() {
        List<Double> ref = new ArrayList<>();
        List<Double> now = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            ref.add(i < 50 ? null : 1.0);
            now.add(i < 10 ? null : 1.0);
        }
        PopulationStability.Distribution d = PopulationStability.reference(ref, 10);
        assertThat(d.missing()).isCloseTo(0.5, within(1e-12));
        // presentes 0.5 → 0.9 e ausentes 0.5 → 0.1
        double expected = (0.9 - 0.5) * Math.log(0.9 / 0.5) + (0.1 - 0.5) * Math.log(0.1 / 0.5);
        assertThat(PopulationStability.psi(d, now)).isCloseTo(expected, within(1e-9));
    }
}
