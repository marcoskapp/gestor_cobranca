package br.com.gestorcobranca.assistant.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Quebra precoce sobre os acordos expostos ao risco; os em maturação ficam fora do denominador. */
class EarlyBreakTallyTest {

    @Test
    void rateUsesOnlyExposedAgreements() {
        EarlyBreakTally tally = new EarlyBreakTally(10, 4, 1);
        assertThat(tally.rate()).isEqualByComparingTo("0.25");
        assertThat(tally.maturing()).isEqualTo(6);
    }

    @Test
    void noExposedAgreementMeansNoRate() {
        EarlyBreakTally tally = new EarlyBreakTally(3, 0, 0);
        assertThat(tally.rate()).isNull();
        assertThat(tally.maturing()).isEqualTo(3);
        assertThat(new EarlyBreakTally(0, 0, 0).rate()).isNull();
    }

    @Test
    void rateIsRoundedToFourDecimals() {
        assertThat(new EarlyBreakTally(3, 3, 1).rate()).isEqualByComparingTo("0.3333");
    }

    @Test
    void inconsistentCountsAreRejected() {
        assertThatThrownBy(() -> new EarlyBreakTally(2, 3, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EarlyBreakTally(5, 2, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EarlyBreakTally(-1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
