package br.com.gestorcobranca.settlement.domain.service;

import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LargestRemainderAndCycleTest {

    @Test
    void splitIsExactAndProportional() {
        assertThat(LargestRemainder.split(Money.of("10.00"), List.of(Money.of("1.00"), Money.of("1.00"), Money.of("1.00"))))
                .containsExactly(Money.of("3.34"), Money.of("3.33"), Money.of("3.33"));
        assertThat(LargestRemainder.split(Money.of("50.00"), List.of(Money.of("30.00"), Money.of("70.00"))))
                .containsExactly(Money.of("15.00"), Money.of("35.00"));
        assertThat(LargestRemainder.split(Money.of("100.00"), List.of(Money.of("100.00"))))
                .containsExactly(Money.of("100.00"));
    }

    @Test
    void cycleByContractSettlementDay() {
        // dia 1: mês civil
        assertThat(SettlementCycle.containing(LocalDate.of(2026, 10, 23), 1))
                .isEqualTo(new SettlementCycle.Period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)));
        assertThat(SettlementCycle.lastClosed(LocalDate.of(2026, 10, 23), 1))
                .isEqualTo(new SettlementCycle.Period(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)));
        // dia 10: de 10 a 9 do mês seguinte
        assertThat(SettlementCycle.containing(LocalDate.of(2026, 10, 23), 10))
                .isEqualTo(new SettlementCycle.Period(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 11, 9)));
        assertThat(SettlementCycle.containing(LocalDate.of(2026, 10, 5), 10))
                .isEqualTo(new SettlementCycle.Period(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 9)));
        assertThat(SettlementCycle.lastClosed(LocalDate.of(2026, 10, 23), 10))
                .isEqualTo(new SettlementCycle.Period(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 10, 9)));
    }
}
