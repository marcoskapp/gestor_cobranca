package br.com.gestorcobranca.legal.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtrajudicialMeasureTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    @Test
    void creditBureauListingRequiresPriorNoticeWithMinimumLead() {
        ExtrajudicialMeasure none = new ExtrajudicialMeasure(Ids.newId(), Ids.newId(), MeasureType.CREDIT_BUREAU_LISTING,
                Set.of(Ids.newId()), null, Money.of("100.00"), "Birô", null, null, "manual");
        assertThatThrownBy(() -> none.submit(TODAY, "P1", null, TODAY, 10))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("legal_measure.prior_notice_required");
        ExtrajudicialMeasure recent = new ExtrajudicialMeasure(Ids.newId(), Ids.newId(), MeasureType.CREDIT_BUREAU_LISTING,
                Set.of(Ids.newId()), null, Money.of("100.00"), "Birô", TODAY.minusDays(9), null, "manual");
        assertThatThrownBy(() -> recent.submit(TODAY, "P1", null, TODAY, 10))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("legal_measure.prior_notice_too_recent");
        ExtrajudicialMeasure ok = new ExtrajudicialMeasure(Ids.newId(), Ids.newId(), MeasureType.CREDIT_BUREAU_LISTING,
                Set.of(Ids.newId()), null, Money.of("100.00"), "Birô", TODAY.minusDays(10), null, "manual");
        assertThat(ok.submit(TODAY, "P1", null, TODAY, 10)).isEqualTo(MeasureStatus.PLANNED);
        assertThat(ok.getStatus()).isEqualTo(MeasureStatus.SUBMITTED);
    }

    @Test
    void lifecycleIsValidated() {
        ExtrajudicialMeasure protest = new ExtrajudicialMeasure(Ids.newId(), Ids.newId(), MeasureType.PROTEST,
                Set.of(Ids.newId()), null, Money.of("100.00"), "1º Tabelião", null, null, "manual");
        assertThatThrownBy(() -> protest.markEffective(TODAY, TODAY, null)).isInstanceOf(ConflictException.class);
        protest.submit(TODAY.minusDays(3), "123", null, TODAY, 10);
        assertThatThrownBy(() -> protest.markEffective(TODAY.minusDays(5), TODAY, null))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("legal_measure.date_invalid");
        protest.markEffective(TODAY, TODAY, "lavrado");
        assertThat(protest.getStatus()).isEqualTo(MeasureStatus.EFFECTIVE);
        assertThatThrownBy(() -> protest.resolve(MeasureStatus.CANCELLED, TODAY, TODAY, "x"))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("legal_measure.transition_invalid");
        protest.resolve(MeasureStatus.REMOVED, TODAY, TODAY, "pago");
        assertThat(protest.getStatus().isFinal()).isTrue();
    }

    @Test
    void collateralOnlyForEnforcement() {
        assertThatThrownBy(() -> new ExtrajudicialMeasure(Ids.newId(), Ids.newId(), MeasureType.COLLATERAL_ENFORCEMENT,
                Set.of(Ids.newId()), null, Money.of("1.00"), null, null, null, "manual"))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("legal_measure.collateral_required");
        assertThatThrownBy(() -> new ExtrajudicialMeasure(Ids.newId(), Ids.newId(), MeasureType.PROTEST,
                Set.of(Ids.newId()), Ids.newId(), Money.of("1.00"), null, null, null, "manual"))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("legal_measure.collateral_not_allowed");
    }
}
