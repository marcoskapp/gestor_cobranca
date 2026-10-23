package br.com.gestorcobranca.compliance.domain.model;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class WeekDaysTest {

    @Test
    void canonicalFormIsOrderedAndRoundTrips() {
        EnumSet<DayOfWeek> days = EnumSet.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY);
        assertThat(WeekDays.format(days)).isEqualTo("MON,WED,SUN");
        assertThat(WeekDays.parse("MON,WED,SUN")).isEqualTo(days);
        assertThat(WeekDays.format(EnumSet.allOf(DayOfWeek.class))).hasSize(27);
    }
}
