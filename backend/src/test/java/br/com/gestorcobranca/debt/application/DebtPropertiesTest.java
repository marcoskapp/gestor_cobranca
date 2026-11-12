package br.com.gestorcobranca.debt.application;

import br.com.gestorcobranca.debt.application.DebtProperties.AmountAdjustment;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Janela do limite acumulado e validade da proposta só em dias inteiros (telas, manual e tour falam em dias). */
class DebtPropertiesTest {

    @Test
    void defaultsAreThirtyAndFifteenDays() {
        AmountAdjustment defaults = new DebtProperties(null, null, null, null).amountAdjustment();
        assertThat(defaults.cumulativeWindow()).isEqualTo(Duration.ofDays(30));
        assertThat(defaults.proposalValidity()).isEqualTo(Duration.ofDays(15));
    }

    @Test
    void onlyWholePositiveDaysAreAccepted() {
        assertThat(new AmountAdjustment(Duration.ofDays(7), Duration.ofDays(1), null).cumulativeWindow())
                .isEqualTo(Duration.ofDays(7));
        assertThatThrownBy(() -> new AmountAdjustment(Duration.ofHours(36), null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cumulative-window");
        assertThatThrownBy(() -> new AmountAdjustment(null, Duration.ofDays(15).plusMinutes(1), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("proposal-validity");
        assertThatThrownBy(() -> new AmountAdjustment(Duration.ZERO, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AmountAdjustment(null, Duration.ofDays(-1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
