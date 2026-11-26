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
    void theDecisionCarriesTheExceededLimitAndHowMuchItPassesBy() {
        var single = ExternalReviewPolicy.decide(Money.of("900.00"), Money.ZERO, Money.ZERO, limits);
        assertThat(single.limit()).isEqualTo(Money.of("500.00"));
        assertThat(single.considered()).isEqualTo(Money.of("900.00"));
        assertThat(single.excess()).isEqualTo(Money.of("400.00"));
        // soma da janela: o valor considerado é a soma com o pagamento novo
        var destination = ExternalReviewPolicy.decide(Money.of("450.00"), Money.of("450.00"), Money.ZERO, limits);
        assertThat(destination.considered()).isEqualTo(Money.of("900.00"));
        assertThat(destination.excess()).isEqualTo(Money.of("400.00"));
        var auto = ExternalReviewPolicy.decide(Money.of("10.00"), Money.ZERO, Money.ZERO, limits);
        assertThat(auto.limit()).isNull();
        assertThat(auto.excess()).isNull();
    }

    @Test
    void spreadingAcrossDestinationsIsCaughtByTheInformerTotal() {
        assertThat(ExternalReviewPolicy.decide(Money.of("400.00"), Money.ZERO, Money.of("1600.00"), limits)
                .reviewRequired()).isFalse();
        assertThat(ExternalReviewPolicy.decide(Money.of("400.00"), Money.ZERO, Money.of("1600.01"), limits).reason())
                .isEqualTo(Reason.INFORMER_CUMULATIVE);
    }

    @Test
    void creditorIntegrationTotalSumsAllTokensOfTheCreditor() {
        Limits integration = new Limits(Money.of("500.00"), Money.of("500.00"), Money.of("2000.00"), Money.of("2000.00"));
        // cada token abaixo do seu teto, mas a soma do credor passa do teto por credor
        var decision = ExternalReviewPolicy.decide(Money.of("400.00"), Money.ZERO, Money.of("400.00"),
                Money.of("1800.00"), integration);
        assertThat(decision.reason()).isEqualTo(Reason.CREDITOR_CUMULATIVE);
        assertThat(decision.limit()).isEqualTo(Money.of("2000.00"));
        assertThat(decision.considered()).isEqualTo(Money.of("2200.00"));
        assertThat(ExternalReviewPolicy.decide(Money.of("200.00"), Money.ZERO, Money.of("200.00"), Money.of("1800.00"),
                integration).reviewRequired()).isFalse();
        // fora da integração (sem soma nem teto por credor) a regra não se aplica
        assertThat(ExternalReviewPolicy.decide(Money.of("400.00"), Money.ZERO, Money.of("400.00"), limits)
                .reviewRequired()).isFalse();
        // os limites por pagamento, destino e quem informa continuam vindo antes
        assertThat(ExternalReviewPolicy.decide(Money.of("600.00"), Money.ZERO, Money.ZERO, Money.of("1800.00"),
                integration).reason()).isEqualTo(Reason.AMOUNT);
    }

    @Test
    void recentCreditorReversalAlwaysGoesToReviewWhateverTheAmount() {
        // RS-120: "informar pago e estornar" — depois do estorno, qualquer informe do mesmo credor/devedor vai à revisão
        var d = ExternalReviewPolicy.decide(Money.of("1.00"), Money.ZERO, Money.ZERO, null, true, limits);
        assertThat(d.reviewRequired()).isTrue();
        assertThat(d.reason()).isEqualTo(Reason.RECENT_CREDITOR_REVERSAL);
        assertThat(d.limit()).isEqualTo(Money.ZERO);
        assertThat(d.considered()).isEqualTo(Money.of("1.00"));
        assertThat(ExternalReviewPolicy.decide(Money.of("1.00"), Money.ZERO, Money.ZERO, null, false, limits)
                .reviewRequired()).isFalse();
    }
}
