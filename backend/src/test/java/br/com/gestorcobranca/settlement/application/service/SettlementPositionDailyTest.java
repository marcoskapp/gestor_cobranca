package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.settlement.application.port.in.SettlementPositionUseCases.DailyPoint;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementPositionDailyTest {

    @Test
    void emptyPeriodHasOnePointPerDayWithAdjustmentsOnTheLastDay() {
        List<DailyPoint> points = SettlementPositionService.daily(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 5),
                List.of(), Map.of(), List.of(), Money.of("10.00"));
        assertThat(points).hasSize(5);
        assertThat(points.getFirst().netTransfer()).isEqualByComparingTo("0.00");
        assertThat(points.get(3).netTransfer()).isEqualByComparingTo("0.00");
        assertThat(points.getLast().netTransfer()).isEqualByComparingTo("10.00");
        assertThat(points.getLast().date()).isEqualTo(LocalDate.of(2026, 3, 5));
    }

    @Test
    void periodEndingBeforeStartHasNoPoints() {
        assertThat(SettlementPositionService.daily(LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 4), List.of(),
                Map.of(), List.of(), Money.ZERO)).isEmpty();
    }
}
