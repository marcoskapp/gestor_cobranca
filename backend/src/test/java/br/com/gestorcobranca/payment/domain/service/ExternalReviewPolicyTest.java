package br.com.gestorcobranca.payment.domain.service;

import br.com.gestorcobranca.payment.domain.service.ExternalReviewPolicy.Limits;
import br.com.gestorcobranca.payment.domain.service.ExternalReviewPolicy.Reason;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalReviewPolicyTest {

    private final Limits limits = new Limits(Money.of("500.00"), Money.of("500.00"), Money.of("2000.00"));

    @Test
    void singlePaymentAboveTheThresholdNeedsReview() {
        assertThat(ExternalReviewPolicy.decide(Money.of("500.01"), Money.ZERO, Money.ZERO, limits).reason())
                .isEqualTo(Reason.AMOUNT);
        assertThat(ExternalReviewPolicy.decide(Money.of("500.00"), Money.ZERO, Money.ZERO, limits).reviewRequired())
                .isFalse();
    }

    @Test
    void splittingForTheSameDestinationIsCaughtByTheAccumulatedTotal() {
        // 7 × R$ 450 para quitar R$ 3.000: só o primeiro passa sem segunda pessoa
        assertThat(ExternalReviewPolicy.decide(Money.of("450.00"), Money.ZERO, Money.ZERO, limits).reviewRequired()).isFalse();
        assertThat(ExternalReviewPolicy.decide(Money.of("450.00"), Money.of("450.00"), Money.of("450.00"), limits).reason())
                .isEqualTo(Reason.DESTINATION_CUMULATIVE);
        assertThat(ExternalReviewPolicy.decide(Money.of("50.00"), Money.of("450.00"), Money.of("450.00"), limits)
                .reviewRequired()).isFalse();
    }

    @Test
    void spreadingAcrossDestinationsIsCaughtByTheInformerTotal() {
        assertThat(ExternalReviewPolicy.decide(Money.of("400.00"), Money.ZERO, Money.of("1600.00"), limits)
                .reviewRequired()).isFalse();
        assertThat(ExternalReviewPolicy.decide(Money.of("400.00"), Money.ZERO, Money.of("1600.01"), limits).reason())
                .isEqualTo(Reason.INFORMER_CUMULATIVE);
    }
}
