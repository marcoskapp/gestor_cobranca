package br.com.gestorcobranca.publicapi.domain.platform;

import br.com.gestorcobranca.publicapi.domain.model.platform.PlatformBillingPolicy;
import br.com.gestorcobranca.publicapi.domain.model.platform.PlatformPriceTerms;
import br.com.gestorcobranca.publicapi.domain.model.platform.VolumeTier;
import br.com.gestorcobranca.publicapi.domain.service.ChargeLines;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.DomainException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Validação da política da plataforma: vigência, valores, casas decimais e faixas de volume. */
class PlatformPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    private static PlatformPriceTerms terms(BigDecimal pricePerRequest, List<VolumeTier> tiers) {
        return new PlatformPriceTerms(5, new BigDecimal("100"), 0, pricePerRequest, tiers, 0, BigDecimal.ZERO, 0,
                BigDecimal.ZERO, 0, BigDecimal.ZERO);
    }

    @Test
    void valuesAreNormalizedAndValidated() {
        PlatformPriceTerms t = terms(new BigDecimal("0.0015"), List.of());
        assertThat(t.monthlyFee()).isEqualTo(new BigDecimal("100.00"));
        assertThat(t.pricePerRequest()).isEqualTo(new BigDecimal("0.001500"));
        assertThat(t.tieredRequests()).isFalse();
        assertThatThrownBy(() -> new PlatformPriceTerms(32, BigDecimal.ZERO, 0, BigDecimal.ZERO, List.of(), 0,
                BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new PlatformPriceTerms(5, new BigDecimal("1.001"), 0, BigDecimal.ZERO, List.of(), 0,
                BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> terms(new BigDecimal("0.0000001"), List.of())).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> terms(new BigDecimal("-1"), List.of())).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new PlatformPriceTerms(5, BigDecimal.ZERO, -1, BigDecimal.ZERO, List.of(), 0,
                BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void tiersMustBeAscendingEndUnboundedAndReplaceTheSinglePrice() {
        PlatformPriceTerms ok = terms(null, List.of(new VolumeTier(10L, new BigDecimal("0.02")),
                new VolumeTier(null, new BigDecimal("0.01"))));
        assertThat(ok.tieredRequests()).isTrue();
        assertThat(ok.requestTiers().get(0).unitPrice()).isEqualTo(new BigDecimal("0.020000"));
        // preço único junto com faixas é ambíguo
        assertThatThrownBy(() -> terms(BigDecimal.ONE, ok.requestTiers())).isInstanceOf(BusinessRuleException.class);
        // sem faixas, o preço único é obrigatório
        assertThatThrownBy(() -> terms(null, List.of())).isInstanceOf(BusinessRuleException.class);
        // última faixa sem limite; limites crescentes e positivos
        assertThatThrownBy(() -> terms(null, List.of(new VolumeTier(10L, BigDecimal.ONE))))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> terms(null, List.of(new VolumeTier(10L, BigDecimal.ONE),
                new VolumeTier(10L, BigDecimal.ONE), new VolumeTier(null, BigDecimal.ONE))))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> terms(null, List.of(new VolumeTier(0L, BigDecimal.ONE),
                new VolumeTier(null, BigDecimal.ONE)))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> terms(null, java.util.stream.LongStream.rangeClosed(1, 11)
                .mapToObj(i -> new VolumeTier(i == 11 ? null : i * 10, BigDecimal.ONE)).toList()))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void termStartIsFixedAfterTheFirstStatementAndTheEndIsNeverInThePast() {
        PlatformBillingPolicy p = new PlatformBillingPolicy(LocalDate.of(2026, 1, 1), null, TODAY);
        p.changeTerm(LocalDate.of(2026, 2, 1), null, false, TODAY);
        assertThat(p.getStartDate()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThatThrownBy(() -> p.changeTerm(LocalDate.of(2026, 3, 1), null, true, TODAY))
                .isInstanceOf(DomainException.class);
        p.changeTerm(LocalDate.of(2026, 2, 1), TODAY, true, TODAY);
        assertThat(p.getEndDate()).isEqualTo(TODAY);
        assertThatThrownBy(() -> p.changeTerm(LocalDate.of(2026, 2, 1), TODAY.minusDays(1), true, TODAY))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new PlatformBillingPolicy(TODAY, TODAY.minusDays(1), TODAY))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new PlatformBillingPolicy(null, null, TODAY)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void sharedChargeRules() {
        assertThat(ChargeLines.excess(10, 12)).isZero();
        assertThat(ChargeLines.excess(15, 12)).isEqualTo(3);
        assertThat(ChargeLines.amount(3, new BigDecimal("0.005"))).isEqualByComparingTo("0.02");
        assertThat(ChargeLines.amount(1, new BigDecimal("0.005"))).isEqualByComparingTo("0.00");
        assertThat(ChargeLines.allowancePercent(5, 0)).isNull();
        assertThat(ChargeLines.allowancePercent(1, 3)).isEqualByComparingTo("33.33");
    }
}
