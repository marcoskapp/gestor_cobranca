package br.com.gestorcobranca.shared.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyTest {

    @Test
    void normalizesScaleWithBankersRounding() {
        assertThat(Money.of("10.005").amount()).isEqualByComparingTo("10.00");
        assertThat(Money.of("10.015").amount()).isEqualByComparingTo("10.02");
        assertThat(Money.of("1").amount().scale()).isEqualTo(2);
    }

    @Test
    void equalityIgnoresInputScale() {
        assertThat(Money.of("10")).isEqualTo(Money.of("10.00"));
    }

    @Test
    void splitDistributesCentsWithoutLosingValue() {
        Money[] parts = Money.of("100.00").split(3);
        assertThat(parts).extracting(Money::amount)
                .containsExactly(new BigDecimal("33.34"), new BigDecimal("33.33"), new BigDecimal("33.33"));
        assertThat(Arrays.stream(parts).reduce(Money.ZERO, Money::plus)).isEqualTo(Money.of("100.00"));
    }

    @Test
    void splitWorksForNegativeAmounts() {
        Money[] parts = Money.of("-0.05").split(2);
        assertThat(Arrays.stream(parts).reduce(Money.ZERO, Money::plus)).isEqualTo(Money.of("-0.05"));
    }

    @Test
    void appliesAndComputesPercentages() {
        assertThat(Money.of("200.00").percent(Percentage.of("12.5"))).isEqualTo(Money.of("25.00"));
        assertThat(Money.of("30.00").ratioOf(Money.of("200.00"))).isEqualTo(Percentage.of("15"));
        assertThat(Money.of("1").ratioOf(Money.ZERO)).isEqualTo(Percentage.ZERO);
    }

    @Test
    void convertsCents() {
        assertThat(Money.ofCents(12345)).isEqualTo(Money.of("123.45"));
        assertThat(Money.of("123.45").toCents()).isEqualTo(12345);
    }
}
