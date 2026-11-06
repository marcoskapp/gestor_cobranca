package br.com.gestorcobranca.payment.application;

import br.com.gestorcobranca.payment.application.PaymentProperties.ReviewStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Janela dos pagamentos recusados só em dias inteiros (a tela e o manual falam em "nos últimos N dias"). */
class PaymentReviewStatusPropertiesTest {

    @Test
    void defaultIsThirtyDaysAndOnlyWholeDaysAreAccepted() {
        assertThat(new ReviewStatus(null, null).rejectedWindow()).isEqualTo(Duration.ofDays(30));
        assertThat(new ReviewStatus(Duration.ofDays(7), 5).rejectedWindow()).isEqualTo(Duration.ofDays(7));
        assertThatThrownBy(() -> new ReviewStatus(Duration.ofHours(36), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dias inteiros");
        assertThatThrownBy(() -> new ReviewStatus(Duration.ZERO, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
