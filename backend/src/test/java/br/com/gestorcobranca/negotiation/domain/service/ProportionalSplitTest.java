package br.com.gestorcobranca.negotiation.domain.service;

import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProportionalSplitTest {

    @Test
    void largestRemainderKeepsTheExactTotalAndCaps() {
        List<Money> parts = ProportionalSplit.split(Money.of("100.00"),
                List.of(Money.of("1.00"), Money.of("1.00"), Money.of("1.00")));
        assertThat(parts).containsExactly(Money.of("33.34"), Money.of("33.33"), Money.of("33.33"));
        List<Money> capped = ProportionalSplit.split(Money.of("10.00"), List.of(Money.of("7.00"), Money.of("3.00")));
        assertThat(capped).containsExactly(Money.of("7.00"), Money.of("3.00"));
        List<Money> small = ProportionalSplit.split(Money.of("0.01"), List.of(Money.of("5.00"), Money.of("5.00")));
        assertThat(small).containsExactly(Money.of("0.01"), Money.ZERO);
        assertThat(ProportionalSplit.split(Money.of("5.00"), List.of(Money.ZERO, Money.ZERO)))
                .containsExactly(Money.of("5.00"), Money.ZERO);
    }
}
