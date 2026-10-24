package br.com.gestorcobranca.ingestion.domain.model.review;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Mediana e MAD em escala logarítmica, conferidos à mão:
 * <pre>
 * valores 100, 200, 400, 800, 1600 → ln = ln100 + k·ln2 (k = 0..4)
 *   mediana = ln 400; desvios = 2ln2, ln2, 0, ln2, 2ln2 → MAD = ln 2 = 0,693147
 *   σ = 1,4826 × 0,693147 = 1,027660
 *   z(6.400)  = ln 16 / σ  = 2,772589 / 1,027660 = 2,6980  (dentro)
 *   z(40.000) = ln 100 / σ = 4,605170 / 1,027660 = 4,4812  (atípico; ÷100 = 400, z = 0)
 * valores 10, 20, 40, 80 → mediana = (ln20 + ln40)/2 = ln √800 = ln 28,2843; desvios = 1,5ln2, 0,5ln2, 0,5ln2, 1,5ln2
 *   MAD = (0,5 + 1,5)/2 · ln2 = ln 2
 * </pre>
 */
class LogScaleStatsTest {

    private static final double LN2 = Math.log(2);

    @Test
    void oddSampleMatchesHandCalculation() {
        LogScaleStats s = LogScaleStats.of(new double[]{1600, 100, 800, 200, 400});
        assertThat(s.size()).isEqualTo(5);
        assertThat(s.median()).isCloseTo(400, within(1e-9));
        assertThat(s.mad()).isCloseTo(LN2, within(1e-12));
        assertThat(s.sigma(0.5)).isCloseTo(1.027660, within(1e-6));
        assertThat(s.z(6400, 0.5)).isCloseTo(2.6980, within(1e-4));
        assertThat(s.z(40000, 0.5)).isCloseTo(4.4812, within(1e-4));
        assertThat(s.z(400, 0.5)).isCloseTo(0, within(1e-12));
        assertThat(s.z(25, 0.5)).isCloseTo(-2.6980, within(1e-4));
        // faixa típica ±2σ: 400 · e^(±2,055320) = 51,22 … 3.123,73
        assertThat(s.low(2, 0.5)).isCloseTo(51.22, within(0.01));
        assertThat(s.high(2, 0.5)).isCloseTo(3123.73, within(0.01));
    }

    @Test
    void evenSampleUsesTheMeanOfTheTwoCentralValues() {
        LogScaleStats s = LogScaleStats.of(new double[]{10, 20, 40, 80});
        assertThat(s.median()).isCloseTo(Math.sqrt(800), within(1e-9));
        assertThat(s.mad()).isCloseTo(LN2, within(1e-12));
    }

    @Test
    void concentratedSampleUsesTheSigmaFloor() {
        LogScaleStats s = LogScaleStats.of(new double[]{100, 100, 100, 100, 100});
        assertThat(s.mad()).isZero();
        assertThat(s.sigma(0.5)).isEqualTo(0.5);
        // ×10 → z = ln10 / 0,5 = 4,6052; ×1,5 → z = ln1,5 / 0,5 = 0,8109
        assertThat(s.z(1000, 0.5)).isCloseTo(4.6052, within(1e-4));
        assertThat(s.z(150, 0.5)).isCloseTo(0.8109, within(1e-4));
    }

    @Test
    void ignoresNonPositiveValues() {
        LogScaleStats s = LogScaleStats.of(new double[]{0, -5, 100, Double.NaN});
        assertThat(s.size()).isEqualTo(1);
        assertThat(LogScaleStats.of(new double[0]).size()).isZero();
    }
}
