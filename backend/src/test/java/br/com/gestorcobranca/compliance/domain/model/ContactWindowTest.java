package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContactWindowTest {

    // 2026-10-23 é sexta-feira
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 23);
    private static final Set<DayOfWeek> WEEKDAYS = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);

    @Test
    void sameDayWindowIsHalfOpen() {
        ContactWindow window = new ContactWindow(LocalTime.of(8, 0), LocalTime.of(20, 0), WEEKDAYS);
        assertThat(window.contains(FRIDAY.atTime(7, 59))).isFalse();
        assertThat(window.contains(FRIDAY.atTime(8, 0))).isTrue();
        assertThat(window.contains(FRIDAY.atTime(19, 59))).isTrue();
        assertThat(window.contains(FRIDAY.atTime(20, 0))).isFalse();
        assertThat(window.contains(FRIDAY.plusDays(1).atTime(10, 0))).isFalse();
    }

    @Test
    void windowCrossingMidnightBelongsToTheOpeningDay() {
        ContactWindow window = new ContactWindow(LocalTime.of(20, 0), LocalTime.of(2, 0), WEEKDAYS);
        // sexta 23h e madrugada de sábado pertencem à janela aberta na sexta
        assertThat(window.openingDate(FRIDAY.atTime(23, 0))).contains(FRIDAY);
        assertThat(window.openingDate(FRIDAY.plusDays(1).atTime(1, 30))).contains(FRIDAY);
        assertThat(window.contains(FRIDAY.plusDays(1).atTime(2, 0))).isFalse();
        // sábado 20h não abre (sábado não permitido); domingo 01h idem
        assertThat(window.contains(FRIDAY.plusDays(1).atTime(20, 0))).isFalse();
        assertThat(window.contains(FRIDAY.plusDays(2).atTime(1, 0))).isFalse();
        // segunda 01h pertence a domingo, que não é permitido
        assertThat(window.contains(FRIDAY.plusDays(3).atTime(1, 0))).isFalse();
        assertThat(window.contains(FRIDAY.atTime(10, 0))).isFalse();
    }

    @Test
    void equalStartAndEndMeansWholeDay() {
        ContactWindow window = new ContactWindow(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, EnumSet.of(DayOfWeek.FRIDAY));
        assertThat(window.contains(FRIDAY.atTime(0, 0))).isTrue();
        assertThat(window.contains(FRIDAY.atTime(23, 59))).isTrue();
        assertThat(window.contains(FRIDAY.plusDays(1).atTime(0, 0))).isFalse();
        assertThat(window.nextOpening(FRIDAY.plusDays(1).atTime(9, 0))).isEqualTo(FRIDAY.plusDays(7).atStartOfDay());
    }

    @Test
    void nextOpeningSkipsDisallowedDays() {
        ContactWindow window = new ContactWindow(LocalTime.of(8, 0), LocalTime.of(20, 0), WEEKDAYS);
        assertThat(window.nextOpening(FRIDAY.atTime(6, 0))).isEqualTo(FRIDAY.atTime(8, 0));
        assertThat(window.nextOpening(FRIDAY.atTime(21, 0))).isEqualTo(FRIDAY.plusDays(3).atTime(8, 0));
        LocalDateTime inside = FRIDAY.atTime(9, 15);
        assertThat(window.nextOpening(inside)).isEqualTo(inside);
        assertThat(window.firstOpeningAfter(FRIDAY)).isEqualTo(FRIDAY.plusDays(3).atTime(8, 0));
    }

    @Test
    void tenantWindowAddsWeekendDaysByConfiguration() {
        ContactWindow window = ContactWindow.ofTenant(LocalTime.of(8, 0), LocalTime.of(20, 0), true, false);
        assertThat(window.days()).contains(DayOfWeek.SATURDAY).doesNotContain(DayOfWeek.SUNDAY);
        assertThatThrownBy(() -> new ContactWindow(LocalTime.NOON, LocalTime.MIDNIGHT, Set.of()))
                .isInstanceOf(BusinessRuleException.class);
    }
}
