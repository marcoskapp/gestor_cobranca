package br.com.gestorcobranca.campaign.domain.service;

import br.com.gestorcobranca.campaign.domain.service.ExperimentStatistics.Interval;
import br.com.gestorcobranca.campaign.domain.service.ExperimentStatistics.Mean;
import br.com.gestorcobranca.campaign.domain.service.ExperimentStatistics.Proportion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Valores de referência: Newcombe (1998), métodos 3 (Wilson) e 10 (diferença). */
class ExperimentStatisticsTest {

    @Test
    void wilsonIntervalMatchesReference() {
        // 81/263: Newcombe 1998, tabela I — 0,2553 a 0,3662
        Proportion p = ExperimentStatistics.proportion(81, 263);
        assertThat(p.interval().estimate()).isCloseTo(0.3080, within(0.0001));
        assertThat(p.interval().lower()).isCloseTo(0.2553, within(0.0005));
        assertThat(p.interval().upper()).isCloseTo(0.3662, within(0.0005));
        // 0/10 continua com limite superior positivo (não colapsa como Wald)
        Proportion zero = ExperimentStatistics.proportion(0, 10);
        assertThat(zero.interval().lower()).isZero();
        assertThat(zero.interval().upper()).isCloseTo(0.2775, within(0.0005));
    }

    @Test
    void newcombeDifferenceMatchesReference() {
        // 56/70 − 48/80: Newcombe 1998, tabela II (método 10) — 0,0524 a 0,3339
        Interval d = ExperimentStatistics.difference(ExperimentStatistics.proportion(56, 70),
                ExperimentStatistics.proportion(48, 80));
        assertThat(d.estimate()).isCloseTo(0.2000, within(0.0001));
        assertThat(d.lower()).isCloseTo(0.0524, within(0.0005));
        assertThat(d.upper()).isCloseTo(0.3339, within(0.0005));
        assertThat(d.significant()).isTrue();
        Interval none = ExperimentStatistics.difference(ExperimentStatistics.proportion(10, 100),
                ExperimentStatistics.proportion(11, 100));
        assertThat(none.significant()).isFalse();
    }

    @Test
    void meanIntervalAndWelchDifference() {
        // valores 0, 100, 200 → média 100, desvio 100, meia-amplitude 1,96·100/√3
        Mean m = ExperimentStatistics.mean(3, 300, 0 + 10_000 + 40_000);
        assertThat(m.interval().estimate()).isCloseTo(100, within(1e-9));
        assertThat(m.standardDeviation()).isCloseTo(100, within(1e-9));
        assertThat(m.interval().upper() - 100).isCloseTo(ExperimentStatistics.Z * 100 / Math.sqrt(3), within(1e-9));
        Mean other = ExperimentStatistics.mean(3, 0, 0);
        Interval d = ExperimentStatistics.difference(m, other);
        assertThat(d.estimate()).isCloseTo(100, within(1e-9));
        assertThat(d.upper() - d.estimate()).isCloseTo(ExperimentStatistics.Z * Math.sqrt(10_000 / 3.0), within(1e-9));
        assertThat(ExperimentStatistics.warnings(other, 2)).containsExactly(ExperimentStatistics.NO_VARIANCE);
    }

    @Test
    void smallSamplesAreFlagged() {
        assertThat(ExperimentStatistics.warnings(ExperimentStatistics.proportion(3, 40), 100))
                .containsExactly(ExperimentStatistics.SAMPLE_BELOW_MINIMUM, ExperimentStatistics.FEW_EVENTS);
        assertThat(ExperimentStatistics.warnings(ExperimentStatistics.proportion(30, 200), 100)).isEmpty();
        assertThat(ExperimentStatistics.proportion(0, 0).interval().upper()).isEqualTo(1);
    }
}
