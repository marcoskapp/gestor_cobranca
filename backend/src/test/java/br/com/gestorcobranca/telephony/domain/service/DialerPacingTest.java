package br.com.gestorcobranca.telephony.domain.service;

import br.com.gestorcobranca.shared.domain.Percentage;
import br.com.gestorcobranca.telephony.domain.model.DialerMode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DialerPacingTest {

    static DialerPacing.Decision decide(DialerMode mode, int agents, int inProgress, String maxRatio, long live,
                                        long abandoned, long dialed) {
        return DialerPacing.decide(new DialerPacing.Input(mode, agents, inProgress, new BigDecimal(maxRatio),
                Percentage.of("3"), live, abandoned, dialed, 50));
    }

    @Test
    void progressiveIsOneLinePerFreeAgent() {
        DialerPacing.Decision d = decide(DialerMode.PROGRESSIVE, 4, 1, "1", 100, 10, 400);
        assertThat(d.callsToPlace()).isEqualTo(3);
        assertThat(d.ratio()).isEqualByComparingTo("1");
        assertThat(d.reason()).isEqualTo("progressive");
        assertThat(decide(DialerMode.PROGRESSIVE, 0, 0, "1", 0, 0, 0).reason()).isEqualTo("no_agents");
        assertThat(decide(DialerMode.PROGRESSIVE, 2, 2, "1", 0, 0, 0).reason()).isEqualTo("lines_busy");
    }

    @Test
    void predictiveWarmsUpThenUsesObservedAnswerRateCappedByMaxRatio() {
        assertThat(decide(DialerMode.PREDICTIVE, 5, 0, "3", 5, 0, 10).reason()).isEqualTo("warming_up");
        assertThat(decide(DialerMode.PREDICTIVE, 5, 0, "3", 5, 0, 10).callsToPlace()).isEqualTo(5);
        // 25% de atendimento → razão 4, limitada a 3
        DialerPacing.Decision capped = decide(DialerMode.PREDICTIVE, 5, 2, "3", 100, 1, 400);
        assertThat(capped.ratio()).isEqualByComparingTo("3");
        assertThat(capped.callsToPlace()).isEqualTo(13);
        assertThat(capped.reason()).isEqualTo("predictive");
        // 50% de atendimento → razão 2
        assertThat(decide(DialerMode.PREDICTIVE, 4, 0, "5", 100, 0, 200).ratio()).isEqualByComparingTo("2");
    }

    @Test
    void abandonRateThrottlesPredictive() {
        // 2,5% ≥ 80% de 3% → metade do excedente: razão 1 + (3 − 1)/2 = 2
        DialerPacing.Decision near = decide(DialerMode.PREDICTIVE, 4, 0, "3", 200, 5, 800);
        assertThat(near.reason()).isEqualTo("abandon_near_limit");
        assertThat(near.ratio()).isEqualByComparingTo("2");
        assertThat(near.abandonRate()).isEqualByComparingTo("2.50");
        // 3% (no limite) → progressivo
        DialerPacing.Decision limit = decide(DialerMode.PREDICTIVE, 4, 1, "3", 100, 3, 400);
        assertThat(limit.reason()).isEqualTo("abandon_limit");
        assertThat(limit.ratio()).isEqualByComparingTo("1");
        assertThat(limit.callsToPlace()).isEqualTo(3);
        // amostra pequena não dispara o limite
        assertThat(decide(DialerMode.PREDICTIVE, 4, 0, "3", 10, 3, 40).reason()).isEqualTo("predictive");
    }

    @Test
    void respectsMaxPerTickAndComputesAbandonRate() {
        DialerPacing.Decision d = DialerPacing.decide(new DialerPacing.Input(DialerMode.PREDICTIVE, 40, 0,
                new BigDecimal("5"), Percentage.of("3"), 100, 0, 500, 50));
        assertThat(d.callsToPlace()).isEqualTo(50);
        assertThat(DialerPacing.abandonRatePercent(0, 0)).isEqualByComparingTo("0");
        assertThat(DialerPacing.abandonRatePercent(300, 9)).isEqualByComparingTo("3.00");
    }
}
