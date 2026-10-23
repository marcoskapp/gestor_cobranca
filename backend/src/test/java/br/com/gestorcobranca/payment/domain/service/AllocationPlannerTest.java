package br.com.gestorcobranca.payment.domain.service;

import br.com.gestorcobranca.payment.domain.model.TitleStrategy;
import br.com.gestorcobranca.payment.domain.service.AllocationPlanner.InstallmentBalance;
import br.com.gestorcobranca.payment.domain.service.AllocationPlanner.Plan;
import br.com.gestorcobranca.payment.domain.service.AllocationPlanner.Share;
import br.com.gestorcobranca.payment.domain.service.AllocationPlanner.TitleBalance;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AllocationPlannerTest {

    private final UUID i1 = UUID.randomUUID();
    private final UUID i2 = UUID.randomUUID();
    private final UUID i3 = UUID.randomUUID();
    private final List<InstallmentBalance> installments = List.of(
            new InstallmentBalance(i1, 1, Money.of("100.00")),
            new InstallmentBalance(i2, 2, Money.of("100.00")),
            new InstallmentBalance(i3, 3, Money.of("100.00")));

    enum Component { PRINCIPAL, INTEREST, FINE }

    @Test
    void agreementPaysRequestedInstallmentFirstThenFollowingByNumber() {
        Plan plan = AllocationPlanner.planAgreement(Money.of("150.00"), installments, List.of(i3));
        assertThat(plan.shares()).containsExactly(new Share(i3, Money.of("100.00")), new Share(i1, Money.of("50.00")));
        assertThat(plan.leftover()).isEqualTo(Money.ZERO);
    }

    @Test
    void partialPaymentStaysOnTheInstallmentAndExcessAboveAgreementIsLeftover() {
        Plan partial = AllocationPlanner.planAgreement(Money.of("40.00"), installments, List.of(i2));
        assertThat(partial.shares()).containsExactly(new Share(i2, Money.of("40.00")));
        Plan excess = AllocationPlanner.planAgreement(Money.of("350.00"), installments, List.of());
        assertThat(excess.allocated()).isEqualTo(Money.of("300.00"));
        assertThat(excess.leftover()).isEqualTo(Money.of("50.00"));
        Plan nothing = AllocationPlanner.planAgreement(Money.of("10.00"), List.of(), List.of(i1));
        assertThat(nothing.shares()).isEmpty();
        assertThat(nothing.leftover()).isEqualTo(Money.of("10.00"));
    }

    @Test
    void titlesOldestFirstUpToBalanceWithLeftover() {
        UUID recent = UUID.randomUUID();
        UUID old = UUID.randomUUID();
        List<TitleBalance> titles = List.of(new TitleBalance(recent, LocalDate.of(2026, 9, 1), Money.of("80.00")),
                new TitleBalance(old, LocalDate.of(2026, 1, 1), Money.of("50.00")));
        Plan plan = AllocationPlanner.planTitles(Money.of("100.00"), titles, TitleStrategy.OLDEST_FIRST);
        assertThat(plan.shares()).containsExactly(new Share(old, Money.of("50.00")), new Share(recent, Money.of("50.00")));
        Plan over = AllocationPlanner.planTitles(Money.of("200.00"), titles, null);
        assertThat(over.allocated()).isEqualTo(Money.of("130.00"));
        assertThat(over.leftover()).isEqualTo(Money.of("70.00"));
    }

    @Test
    void titlesProportionalSplitIsExactAndNeverAboveBalance() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        List<TitleBalance> titles = List.of(new TitleBalance(a, null, Money.of("100.00")),
                new TitleBalance(b, null, Money.of("100.00")), new TitleBalance(c, null, Money.of("100.00")));
        Plan plan = AllocationPlanner.planTitles(Money.of("100.00"), titles, TitleStrategy.PROPORTIONAL);
        assertThat(plan.allocated()).isEqualTo(Money.of("100.00"));
        assertThat(plan.shares()).extracting(Share::amount)
                .containsExactly(Money.of("33.34"), Money.of("33.33"), Money.of("33.33"));
        Plan full = AllocationPlanner.planTitles(Money.of("350.00"), titles, TitleStrategy.PROPORTIONAL);
        assertThat(full.allocated()).isEqualTo(Money.of("300.00"));
        assertThat(full.leftover()).isEqualTo(Money.of("50.00"));
    }

    @Test
    void componentsSplitProportionallyAndRefuseAboveTitleBalance() {
        Map<Component, Money> balances = new LinkedHashMap<>();
        balances.put(Component.PRINCIPAL, Money.of("700.00"));
        balances.put(Component.INTEREST, Money.of("200.00"));
        balances.put(Component.FINE, Money.of("100.00"));
        Map<Component, Money> parts = AllocationPlanner.splitComponents(Money.of("100.01"), balances);
        assertThat(parts.values().stream().reduce(Money.ZERO, Money::plus)).isEqualTo(Money.of("100.01"));
        assertThat(parts.get(Component.PRINCIPAL)).isEqualTo(Money.of("70.01"));
        assertThat(parts.get(Component.INTEREST)).isEqualTo(Money.of("20.00"));
        assertThatThrownBy(() -> AllocationPlanner.splitComponents(Money.of("1000.01"), balances))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void largestRemainderKeepsTheTotal() {
        List<Money> parts = LargestRemainder.split(Money.of("0.05"), List.of(Money.of("1.00"), Money.of("1.00"),
                Money.of("1.00")));
        assertThat(parts).containsExactly(Money.of("0.02"), Money.of("0.02"), Money.of("0.01"));
        assertThat(LargestRemainder.split(Money.of("10.00"), List.of(Money.ZERO, Money.ZERO)))
                .containsExactly(Money.ZERO, Money.ZERO);
        assertThatThrownBy(() -> AllocationPlanner.planAgreement(Money.ZERO, installments, List.of()))
                .isInstanceOf(BusinessRuleException.class);
    }
}
