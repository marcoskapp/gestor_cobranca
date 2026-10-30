package br.com.gestorcobranca.publicapi.domain;

import br.com.gestorcobranca.publicapi.domain.model.BillableUsage;
import br.com.gestorcobranca.publicapi.domain.model.PriceTerms;
import br.com.gestorcobranca.publicapi.domain.model.UsageTotals;
import br.com.gestorcobranca.publicapi.domain.service.PriceCalculator;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceCalculatorTest {

    private static final long MB = 1_048_576L;

    private static PriceTerms terms() {
        return new PriceTerms(new BigDecimal("100.00"), 1_000, new BigDecimal("0.010000"), 10, new BigDecimal("0.500000"),
                10, new BigDecimal("0.250000"), 500, new BigDecimal("0.020000"));
    }

    @Test
    void chargesOnlyAboveAllowancesWithIntegerQuantities() {
        PriceCalculator.Result r = PriceCalculator.calculate(terms(), new BillableUsage(1_150, 13, 4, 2_000));
        assertThat(r.lines()).extracting(PriceCalculator.Line::metric)
                .containsExactly("MONTHLY_FEE", "REQUESTS", "MB_IN", "MB_OUT", "RECORDS");
        assertThat(r.lines().get(1).billable()).isEqualTo(150);
        assertThat(r.lines().get(1).amount()).isEqualByComparingTo("1.50");
        assertThat(r.lines().get(2).quantity()).isEqualTo(13);
        assertThat(r.lines().get(2).billable()).isEqualTo(3);
        assertThat(r.lines().get(2).amount()).isEqualByComparingTo("1.50");
        assertThat(r.lines().get(3).amount()).isEqualByComparingTo("0.00");
        assertThat(r.lines().get(4).amount()).isEqualByComparingTo("30.00");
        assertThat(r.total()).isEqualByComparingTo("133.00");
    }

    @Test
    void clientErrorsThrottledAndServerErrorsAreNotBillable() {
        // 1.300 requisições: 100 limitadas (429), 30 com 4xx e 50 com 5xx não contam → 1.120 cobráveis
        UsageTotals totals = new UsageTotals(1_300, 100, 30, 50, 9 * MB, 5 * MB, 8 * MB, 4 * MB, 0);
        assertThat(totals.billableRequests()).isEqualTo(1_120);
        BillableUsage billable = BillableUsage.ofToken(totals);
        assertThat(billable.requests()).isEqualTo(1_120);
        // tráfego cobrável é só o das requisições 2xx/3xx
        assertThat(billable.mbIn()).isEqualTo(8);
        assertThat(billable.mbOut()).isEqualTo(4);
        // só erros do cliente: nada cobrável
        assertThat(new UsageTotals(5, 0, 5, 0, 100, 100, 0, 0, 0).billableRequests()).isZero();
    }

    @Test
    void trafficIsBilledInWholeBinaryMegabytesRoundedUp() {
        assertThat(BillableUsage.megabytes(0)).isZero();
        assertThat(BillableUsage.megabytes(1)).isEqualTo(1);
        assertThat(BillableUsage.megabytes(1_048_576)).isEqualTo(1);
        assertThat(BillableUsage.megabytes(1_048_577)).isEqualTo(2);
        assertThat(BillableUsage.megabytes(1_000_000)).isEqualTo(1);
        assertThat(BillableUsage.megabytes(10 * MB)).isEqualTo(10);
        assertThat(BillableUsage.megabytes(10 * MB + 1)).isEqualTo(11);
    }

    @Test
    void roundingIsPerTokenAndDirectionNotPerRequestNorOnlyPerCreditor() {
        // dois tokens com 1 byte recebido cada e 1,5 MB enviados cada
        UsageTotals tokenA = new UsageTotals(1, 0, 0, 0, 1, MB + MB / 2, 1, MB + MB / 2, 0);
        UsageTotals tokenB = new UsageTotals(1, 0, 0, 0, 1, MB + MB / 2, 1, MB + MB / 2, 0);
        BillableUsage creditor = BillableUsage.sumOfTokens(List.of(tokenA, tokenB));
        // por token: 1 MB recebido + 2 MB enviados; o credor é a soma dos tokens (não o arredondamento do total)
        assertThat(creditor.mbIn()).isEqualTo(2);
        assertThat(creditor.mbOut()).isEqualTo(4);
        assertThat(creditor.requests()).isEqualTo(2);
    }

    @Test
    void sameUsageAndTermsAlwaysProduceTheSameStatement() {
        BillableUsage usage = new BillableUsage(7_777, 118, 95, 4_321);
        assertThat(PriceCalculator.calculate(terms(), usage)).isEqualTo(PriceCalculator.calculate(terms(), usage));
        assertThat(PriceCalculator.calculate(terms(), BillableUsage.ZERO).total()).isEqualByComparingTo("100.00");
    }

    @Test
    void termsRejectNegativeValues() {
        assertThatThrownBy(() -> new PriceTerms(new BigDecimal("-1"), 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0,
                BigDecimal.ZERO, 0, BigDecimal.ZERO)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new PriceTerms(BigDecimal.ZERO, -1, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0,
                BigDecimal.ZERO, 0, BigDecimal.ZERO)).isInstanceOf(BusinessRuleException.class);
    }
}
