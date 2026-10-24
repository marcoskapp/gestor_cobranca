package br.com.gestorcobranca.analytics.domain;

import br.com.gestorcobranca.analytics.domain.model.Dimension;
import br.com.gestorcobranca.analytics.domain.model.Ratio;
import br.com.gestorcobranca.analytics.domain.model.ReportFilter;
import br.com.gestorcobranca.analytics.domain.service.Indicators;
import br.com.gestorcobranca.analytics.domain.service.SpreadsheetSafety;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndicatorsTest {

    @Test
    void ratioKeepsNumeratorAndDenominatorAndRoundsHalfEven() {
        Ratio r = Indicators.ratio(new BigDecimal("1100.00"), new BigDecimal("4000.00"));
        assertThat(r.value()).isEqualByComparingTo("0.2750");
        assertThat(r.numerator()).isEqualByComparingTo("1100.00");
        assertThat(r.denominator()).isEqualByComparingTo("4000.00");
        assertThat(r.unavailableReason()).isNull();
        // 1 ÷ 3 = 0,33333… → 0,3333; 2 ÷ 3 = 0,6667
        assertThat(Indicators.ratio(1, 3).value()).isEqualByComparingTo("0.3333");
        assertThat(Indicators.ratio(2, 3).value()).isEqualByComparingTo("0.6667");
        // 0,00005 exato arredonda para o par
        assertThat(Indicators.ratio(new BigDecimal("1"), new BigDecimal("20000")).value()).isEqualByComparingTo("0.0000");
        assertThat(Indicators.ratio(new BigDecimal("3"), new BigDecimal("20000")).value()).isEqualByComparingTo("0.0002");
    }

    @Test
    void zeroDenominatorIsUnavailableNotZero() {
        Ratio r = Indicators.ratio(5, 0);
        assertThat(r.value()).isNull();
        assertThat(r.unavailableReason()).isEqualTo("no_denominator");
        assertThat(Indicators.ratio(BigDecimal.TEN, null).unavailableReason()).isEqualTo("no_denominator");
    }

    @Test
    void netRecoveryAndHours() {
        assertThat(Indicators.net(new BigDecimal("1300"), new BigDecimal("200"))).isEqualByComparingTo("1100");
        assertThat(Indicators.net(null, null)).isEqualByComparingTo("0");
        assertThat(Indicators.hours(16200)).isEqualByComparingTo("4.50");
        // 2 acordos ÷ 4,5 h = 0,4444
        Ratio perHour = Indicators.perHour(2, 16200);
        assertThat(perHour.value()).isEqualByComparingTo("0.4444");
        assertThat(perHour.denominator()).isEqualByComparingTo("4.50");
        assertThat(Indicators.perHour(1, 0).unavailableReason()).isEqualTo("no_denominator");
    }

    @Test
    void spreadsheetSafetyNeutralizesFormulasButNotNumbers() {
        assertThat(SpreadsheetSafety.neutralize("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(SpreadsheetSafety.neutralize("+55 11")).isEqualTo("'+55 11");
        assertThat(SpreadsheetSafety.neutralize("@cmd")).isEqualTo("'@cmd");
        assertThat(SpreadsheetSafety.neutralize("-1+1")).isEqualTo("'-1+1");
        assertThat(SpreadsheetSafety.neutralize("\tx")).isEqualTo("'\tx");
        assertThat(SpreadsheetSafety.neutralize("-150.25")).isEqualTo("-150.25");
        assertThat(SpreadsheetSafety.neutralize("Banco Alfa")).isEqualTo("Banco Alfa");
        assertThat(SpreadsheetSafety.neutralize(null)).isEmpty();
    }

    @Test
    void filterValidatesPeriodAndSizes() {
        LocalDate d = LocalDate.of(2026, 6, 1);
        assertThatThrownBy(() -> new ReportFilter(null, d, null, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new ReportFilter(d, d.minusDays(1), null, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new ReportFilter(d, d.plusDays(400), null, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new ReportFilter(d, d, null, null, null, null, Set.of("phone'; drop")))
                .isInstanceOf(BusinessRuleException.class);
        Set<UUID> many = IntStream.range(0, 201).mapToObj(i -> UUID.randomUUID()).collect(Collectors.toSet());
        assertThatThrownBy(() -> new ReportFilter(d, d, many, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class);
        ReportFilter f = new ReportFilter(d, d.plusDays(399), null, null, null, null, Set.of("PHONE"));
        assertThat(f.creditorIds()).isEmpty();
        assertThat(f.segmentsBeyondPeople()).isTrue();
    }

    @Test
    void dimensionAcceptsOnlyTheReportList() {
        assertThat(Dimension.parse(null, EnumSet.of(Dimension.CREDITOR))).isEqualTo(Dimension.NONE);
        assertThat(Dimension.parse("creditor", EnumSet.of(Dimension.CREDITOR))).isEqualTo(Dimension.CREDITOR);
        assertThatThrownBy(() -> Dimension.parse("delay_bucket", EnumSet.of(Dimension.CREDITOR)))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> Dimension.parse("1; drop table x", EnumSet.allOf(Dimension.class)))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(Dimension.TEAM.byPerson()).isTrue();
        assertThat(Dimension.MONTH.temporal()).isTrue();
    }
}
