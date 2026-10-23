package br.com.gestorcobranca.legal.domain.service;

import br.com.gestorcobranca.legal.domain.model.CountingMode;
import br.com.gestorcobranca.legal.domain.model.StartEvent;
import br.com.gestorcobranca.legal.domain.service.DeadlineCalculator.ForensicCalendar;
import br.com.gestorcobranca.legal.domain.service.DeadlineCalculator.Suspension;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static br.com.gestorcobranca.legal.domain.model.CountingMode.BUSINESS_DAYS;
import static br.com.gestorcobranca.legal.domain.model.CountingMode.CALENDAR_DAYS;
import static br.com.gestorcobranca.legal.domain.model.StartEvent.INTIMATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CPC arts. 219, 220 e 224 — sexta-feira 23/10/2026 como referência. */
class DeadlineCalculatorTest {

    private static final LocalDate FRI_23_OCT = LocalDate.of(2026, 10, 23);
    private static final ForensicCalendar PLAIN = new ForensicCalendar(Set.of(), List.of(), true);

    private static LocalDate due(LocalDate start, StartEvent event, int term, CountingMode mode, ForensicCalendar c) {
        return DeadlineCalculator.dueDate(start, event, term, mode, null, c);
    }

    @Test
    void businessDaysExcludeStartDayAndWeekends() {
        assertThat(due(FRI_23_OCT, INTIMATION, 5, BUSINESS_DAYS, PLAIN)).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(due(FRI_23_OCT, INTIMATION, 15, BUSINESS_DAYS, PLAIN)).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(due(LocalDate.of(2026, 10, 24), INTIMATION, 1, BUSINESS_DAYS, PLAIN))
                .as("intimação no sábado: conta a partir de segunda").isEqualTo(LocalDate.of(2026, 10, 26));
    }

    @Test
    void holidaysFromTheCalendarDoNotCount() {
        ForensicCalendar finados = new ForensicCalendar(Set.of(LocalDate.of(2026, 11, 2)), List.of(), true);
        assertThat(due(FRI_23_OCT, INTIMATION, 15, BUSINESS_DAYS, finados)).isEqualTo(LocalDate.of(2026, 11, 16));
    }

    @Test
    void djeAvailabilityPublishesOnNextBusinessDay() {
        LocalDate thursday = LocalDate.of(2026, 10, 22);
        assertThat(due(thursday, INTIMATION, 5, BUSINESS_DAYS, PLAIN)).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(due(thursday, StartEvent.DJE_AVAILABILITY, 5, BUSINESS_DAYS, PLAIN))
                .as("disponível 22, publicado 23, conta de 26").isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(due(FRI_23_OCT, StartEvent.DJE_AVAILABILITY, 1, BUSINESS_DAYS, PLAIN))
                .as("disponível sexta, publicado segunda, conta de terça").isEqualTo(LocalDate.of(2026, 10, 27));
    }

    @Test
    void courtSuspensionsAndYearEndRecessSuspendBusinessDayCounting() {
        ForensicCalendar suspended = new ForensicCalendar(Set.of(),
                List.of(new Suspension(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 27))), true);
        assertThat(due(FRI_23_OCT, INTIMATION, 5, BUSINESS_DAYS, suspended)).isEqualTo(LocalDate.of(2026, 11, 3));

        Set<LocalDate> holidays = Set.of(LocalDate.of(2026, 12, 25), LocalDate.of(2027, 1, 1));
        LocalDate friday18Dec = LocalDate.of(2026, 12, 18);
        assertThat(due(friday18Dec, INTIMATION, 5, BUSINESS_DAYS, new ForensicCalendar(holidays, List.of(), true)))
                .as("recesso de 20/12 a 20/01").isEqualTo(LocalDate.of(2027, 1, 27));
        assertThat(due(friday18Dec, INTIMATION, 5, BUSINESS_DAYS, new ForensicCalendar(holidays, List.of(), false)))
                .isEqualTo(LocalDate.of(2026, 12, 28));
    }

    @Test
    void calendarDaysPostponeEndToNextOpenDayButIgnoreRecess() {
        ForensicCalendar finados = new ForensicCalendar(Set.of(LocalDate.of(2026, 11, 2)), List.of(), true);
        assertThat(due(FRI_23_OCT, INTIMATION, 10, CALENDAR_DAYS, finados)).isEqualTo(LocalDate.of(2026, 11, 3));
        ForensicCalendar christmas = new ForensicCalendar(Set.of(LocalDate.of(2026, 12, 25)), List.of(), true);
        assertThat(due(LocalDate.of(2026, 12, 10), INTIMATION, 15, CALENDAR_DAYS, christmas))
                .isEqualTo(LocalDate.of(2026, 12, 28));
    }

    @Test
    void fixedDateFallingOnWeekendMovesToMonday() {
        assertThat(DeadlineCalculator.dueDate(null, null, 0, CountingMode.FIXED_DATE, LocalDate.of(2026, 10, 24), PLAIN))
                .isEqualTo(LocalDate.of(2026, 10, 26));
        assertThatThrownBy(() -> DeadlineCalculator.dueDate(null, null, 0, CountingMode.FIXED_DATE, null, PLAIN))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void validatesTermAndStart() {
        assertThatThrownBy(() -> due(FRI_23_OCT, INTIMATION, 0, BUSINESS_DAYS, PLAIN))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("legal_deadline.term_invalid");
        assertThatThrownBy(() -> due(FRI_23_OCT, INTIMATION, 366, BUSINESS_DAYS, PLAIN))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> due(null, INTIMATION, 5, BUSINESS_DAYS, PLAIN))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("legal_deadline.start_required");
    }

    @Test
    void remainingBusinessDays() {
        assertThat(DeadlineCalculator.businessDaysRemaining(FRI_23_OCT, LocalDate.of(2026, 10, 28), PLAIN)).isEqualTo(3);
        assertThat(DeadlineCalculator.businessDaysRemaining(FRI_23_OCT, FRI_23_OCT, PLAIN)).isZero();
        assertThat(DeadlineCalculator.businessDaysRemaining(FRI_23_OCT, LocalDate.of(2026, 10, 22), PLAIN)).isEqualTo(-1);
        assertThat(DeadlineCalculator.horizon(FRI_23_OCT, 15, BUSINESS_DAYS, null)).isAfter(LocalDate.of(2026, 11, 16));
    }
}
