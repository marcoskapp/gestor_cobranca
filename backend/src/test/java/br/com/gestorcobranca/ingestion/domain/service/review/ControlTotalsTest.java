package br.com.gestorcobranca.ingestion.domain.service.review;

import br.com.gestorcobranca.ingestion.domain.model.review.ComputedTotals;
import br.com.gestorcobranca.ingestion.domain.model.review.ControlTotalsInput;
import br.com.gestorcobranca.ingestion.domain.model.review.MeasureComparison;
import br.com.gestorcobranca.ingestion.domain.model.review.TotalsComparison;
import br.com.gestorcobranca.ingestion.domain.model.review.TotalsMeasure;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlTotalsTest {

    private final ControlTotals totals = new ControlTotals(0, new BigDecimal("0.01"), 0.005);

    private static ComputedTotals computed(long rows, String principal, String balance) {
        return new ComputedTotals(rows, Map.of(TotalsMeasure.PRINCIPAL, new BigDecimal(principal),
                TotalsMeasure.BALANCE, new BigDecimal(balance), TotalsMeasure.CONTRACT_AMOUNT, BigDecimal.ZERO),
                Map.of(TotalsMeasure.PRINCIPAL, 0L));
    }

    @Test
    void matchesWithinRoundingTolerance() {
        TotalsComparison c = totals.compare(new ControlTotalsInput(3L, new BigDecimal("1234.57"), null, null),
                computed(3, "1234.56", "0"));
        assertThat(c.matches()).isTrue();
        assertThat(c.measures()).hasSize(2);
    }

    @Test
    void rowCountHasZeroTolerance() {
        TotalsComparison c = totals.compare(new ControlTotalsInput(4L, null, null, null), computed(3, "0", "0"));
        assertThat(c.matches()).isFalse();
        assertThat(c.mismatched()).extracting(MeasureComparison::measure).containsExactly(TotalsMeasure.ROWS);
        assertThat(c.scaleHint()).isEmpty();
    }

    @Test
    void differenceAboveOneCentBlocks() {
        TotalsComparison c = totals.compare(new ControlTotalsInput(null, new BigDecimal("1234.58"), null, null),
                computed(3, "1234.56", "0"));
        assertThat(c.matches()).isFalse();
        MeasureComparison m = c.mismatched().getFirst();
        assertThat(m.difference()).isEqualByComparingTo("0.02");
        assertThat(m.scaleExponent()).isNull();
    }

    @ParameterizedTest(name = "informado {0} × calculado {1} → 10^{2}")
    @CsvSource({
            "1.00, 100.00, -2",       // 1.00 virou 100: o arquivo foi lido 100× maior
            "1000.00, 1.00, 3",        // 1.000 (mil) lido como 1,00
            "12345.60, 1234.56, 1",
            "1234.56, 123456.00, -2",
            "1234.56, 1234560.00, -3",
            "100.40, 1.00, 2"})       // ±0,5% de 10ⁿ
    void ratioCloseToPowerOfTenPointsToDecimalSeparator(String entered, String calculated, int exponent) {
        TotalsComparison c = totals.compare(new ControlTotalsInput(null, new BigDecimal(entered), null, null),
                computed(1, calculated, "0"));
        assertThat(c.scaleHint()).hasValueSatisfying(m -> assertThat(m.scaleExponent()).isEqualTo(exponent));
    }

    @Test
    void ratioFarFromPowerOfTenIsJustADifference() {
        assertThat(ScaleRatio.exponent(new BigDecimal("101"), new BigDecimal("1"), 0.005)).isEmpty();
        assertThat(ScaleRatio.exponent(new BigDecimal("50"), new BigDecimal("1"), 0.005)).isEmpty();
        assertThat(ScaleRatio.exponent(BigDecimal.ZERO, BigDecimal.ONE, 0.005)).isEmpty();
        assertThat(ScaleRatio.exponent(new BigDecimal("10000"), new BigDecimal("1"), 0.005)).isEmpty();
    }

    @Test
    void requiresAtLeastOneTotalWithAtMostTwoDecimals() {
        assertThatThrownBy(() -> new ControlTotalsInput(null, null, null, null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new ControlTotalsInput(null, new BigDecimal("1.234"), null, null))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new ControlTotalsInput(-1L, null, null, null)).isInstanceOf(BusinessRuleException.class);
        assertThat(new ControlTotalsInput(null, new BigDecimal("1.230"), null, null).entered()).containsKey(TotalsMeasure.PRINCIPAL);
    }
}
