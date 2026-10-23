package br.com.gestorcobranca.incentive.domain.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Carência em dias úteis (segunda a sexta, menos feriados da assessoria). */
class BusinessDaysTest {

    @Test
    void fiveBusinessDaysFromFridayLandsOnTheNextFriday() {
        // sexta 23/10/2026 + 5 dias úteis = sexta 30/10/2026
        assertThat(BusinessDays.plus(LocalDate.of(2026, 10, 23), 5, Set.of())).isEqualTo(LocalDate.of(2026, 10, 30));
    }

    @Test
    void holidaysAreSkipped() {
        // feriados em 28/10 e 02/11 (Finados): 26, 27, 29, 30/10 e 03/11
        assertThat(BusinessDays.plus(LocalDate.of(2026, 10, 23), 5,
                Set.of(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 2)))).isEqualTo(LocalDate.of(2026, 11, 3));
    }

    @Test
    void startingOnWeekendCountsFromTheNextBusinessDay() {
        assertThat(BusinessDays.plus(LocalDate.of(2026, 10, 24), 1, Set.of())).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(BusinessDays.plus(LocalDate.of(2026, 10, 24), 0, Set.of())).isEqualTo(LocalDate.of(2026, 10, 24));
    }

    @Test
    void businessDayRules() {
        assertThat(BusinessDays.isBusinessDay(LocalDate.of(2026, 10, 24), Set.of())).isFalse();
        assertThat(BusinessDays.isBusinessDay(LocalDate.of(2026, 10, 26), Set.of())).isTrue();
        assertThat(BusinessDays.isBusinessDay(LocalDate.of(2026, 11, 2), Set.of(LocalDate.of(2026, 11, 2)))).isFalse();
        assertThatThrownBy(() -> BusinessDays.plus(LocalDate.of(2026, 10, 26), -1, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void horizonAlwaysContainsTheResult() {
        LocalDate start = LocalDate.of(2026, 12, 18);
        assertThat(BusinessDays.horizon(start, 20)).isAfter(BusinessDays.plus(start, 20, Set.of()));
    }
}
