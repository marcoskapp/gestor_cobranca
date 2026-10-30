package br.com.gestorcobranca.publicapi.domain;

import br.com.gestorcobranca.publicapi.domain.service.BillingCycles;
import br.com.gestorcobranca.publicapi.domain.service.BillingCycles.Cycle;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BillingCyclesTest {

    private static final ZoneId ZONE = TimeConfig.BUSINESS_ZONE;

    @Test
    void day31ClosesOnTheLastDayOfShorterMonths() {
        assertThat(BillingCycles.closingDateIn(YearMonth.of(2026, 2), 31)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(BillingCycles.closingDateIn(YearMonth.of(2028, 2), 31)).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(BillingCycles.closingDateIn(YearMonth.of(2026, 4), 31)).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(BillingCycles.closingDateIn(YearMonth.of(2026, 2), 30)).isEqualTo(LocalDate.of(2026, 2, 28));

        // ciclo de fevereiro com dia 31: 01/02 a 28/02; o seguinte começa em 01/03 e fecha em 31/03
        Cycle february = BillingCycles.startingAt(LocalDate.of(2026, 2, 1), 31);
        assertThat(february.end()).isEqualTo(LocalDate.of(2026, 2, 28));
        Cycle march = BillingCycles.startingAt(february.end().plusDays(1), 31);
        assertThat(march.start()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(march.end()).isEqualTo(LocalDate.of(2026, 3, 31));
        // dia 30: fevereiro fecha em 28; março começa em 01/03 e fecha em 30/03
        assertThat(BillingCycles.startingAt(LocalDate.of(2026, 1, 31), 30).end()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(BillingCycles.startingAt(LocalDate.of(2026, 3, 1), 30).end()).isEqualTo(LocalDate.of(2026, 3, 30));
    }

    @Test
    void cycleClosesAtTheEndOfTheClosingDayAndNextStartsAtMidnightInBusinessZone() {
        Cycle cycle = BillingCycles.startingAt(LocalDate.of(2026, 3, 11), 10);
        assertThat(cycle.end()).isEqualTo(LocalDate.of(2026, 4, 10));
        // 00:00 de 11/03 e 00:00 de 11/04 em America/Sao_Paulo (UTC-3)
        assertThat(cycle.from(ZONE)).isEqualTo(Instant.parse("2026-03-11T03:00:00Z"));
        assertThat(cycle.to(ZONE)).isEqualTo(Instant.parse("2026-04-11T03:00:00Z"));
        // 23:59:59 do dia 10 (horário de Brasília) ainda é o ciclo; 00:00 do dia 11 já é o próximo
        assertThat(cycle.endedAt(Instant.parse("2026-04-11T02:59:59Z"), ZONE)).isFalse();
        assertThat(cycle.endedAt(Instant.parse("2026-04-11T03:00:00Z"), ZONE)).isTrue();
        Cycle next = BillingCycles.startingAt(cycle.end().plusDays(1), 10);
        assertThat(next.from(ZONE)).isEqualTo(cycle.to(ZONE));
    }

    @Test
    void cycleStartingOnTheClosingDayEndsTheSameDay() {
        Cycle cycle = BillingCycles.startingAt(LocalDate.of(2026, 3, 10), 10);
        assertThat(cycle.start()).isEqualTo(cycle.end());
    }

    @Test
    void containingDayUsesThePureCalendarOfTheClosingDay() {
        Cycle c = BillingCycles.containing(LocalDate.of(2026, 3, 5), 10);
        assertThat(c.start()).isEqualTo(LocalDate.of(2026, 2, 11));
        assertThat(c.end()).isEqualTo(LocalDate.of(2026, 3, 10));
        Cycle end = BillingCycles.containing(LocalDate.of(2026, 3, 1), 31);
        assertThat(end.start()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(end.end()).isEqualTo(LocalDate.of(2026, 3, 31));
    }

    @Test
    void rejectsInvalidClosingDay() {
        assertThatThrownBy(() -> BillingCycles.startingAt(LocalDate.of(2026, 1, 1), 0))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> BillingCycles.startingAt(LocalDate.of(2026, 1, 1), 32))
                .isInstanceOf(BusinessRuleException.class);
    }
}
