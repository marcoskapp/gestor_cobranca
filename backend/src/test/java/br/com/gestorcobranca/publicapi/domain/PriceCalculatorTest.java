package br.com.gestorcobranca.publicapi.domain;

import br.com.gestorcobranca.publicapi.domain.model.PriceTerms;
import br.com.gestorcobranca.publicapi.domain.model.UsageTotals;
import br.com.gestorcobranca.publicapi.domain.service.PriceCalculator;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceCalculatorTest {

    private static PriceTerms terms() {
        return new PriceTerms(new BigDecimal("100.00"), 1_000, new BigDecimal("0.010000"), 10, new BigDecimal("0.500000"),
                10, new BigDecimal("0.250000"), 500, new BigDecimal("0.020000"));
    }

    @Test
    void chargesOnlyAboveAllowancesExcludingThrottledAndServerErrors() {
        // 1.300 requisições: 100 limitadas e 50 com 5xx não contam → 1.150 cobráveis, 150 acima da franquia
        UsageTotals usage = new UsageTotals(1_300, 100, 30, 50, 12_500_000, 4_000_000, 2_000);
        PriceCalculator.Result r = PriceCalculator.calculate(terms(), usage);
        assertThat(r.lines()).extracting(PriceCalculator.Line::metric)
                .containsExactly("MONTHLY_FEE", "REQUESTS", "MB_IN", "MB_OUT", "RECORDS");
        assertThat(r.lines().get(1).billable()).isEqualByComparingTo("150");
        assertThat(r.lines().get(1).amount()).isEqualByComparingTo("1.50");
        assertThat(r.lines().get(2).quantity()).isEqualByComparingTo("12.5");
        assertThat(r.lines().get(2).amount()).isEqualByComparingTo("1.25");
        assertThat(r.lines().get(3).amount()).isEqualByComparingTo("0.00");
        assertThat(r.lines().get(4).amount()).isEqualByComparingTo("30.00");
        assertThat(r.total()).isEqualByComparingTo("132.75");
    }

    @Test
    void sameUsageAndTermsAlwaysProduceTheSameStatement() {
        UsageTotals usage = new UsageTotals(7_777, 3, 4, 5, 123_456_789, 98_765_432, 4_321);
        assertThat(PriceCalculator.calculate(terms(), usage)).isEqualTo(PriceCalculator.calculate(terms(), usage));
        assertThat(PriceCalculator.calculate(terms(), UsageTotals.ZERO).total()).isEqualByComparingTo("100.00");
    }

    @Test
    void termsRejectNegativeAndOverPreciseValues() {
        assertThatThrownBy(() -> new PriceTerms(new BigDecimal("-1"), 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0,
                BigDecimal.ZERO, 0, BigDecimal.ZERO)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new PriceTerms(BigDecimal.ZERO, 0, new BigDecimal("0.0000001"), 0, BigDecimal.ZERO, 0,
                BigDecimal.ZERO, 0, BigDecimal.ZERO)).isInstanceOf(BusinessRuleException.class);
    }
}
