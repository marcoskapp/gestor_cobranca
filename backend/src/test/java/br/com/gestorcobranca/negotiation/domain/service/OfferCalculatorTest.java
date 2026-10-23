package br.com.gestorcobranca.negotiation.domain.service;

import br.com.gestorcobranca.negotiation.domain.model.calculation.DebtComposition;
import br.com.gestorcobranca.negotiation.domain.model.calculation.TitleSnapshot;
import br.com.gestorcobranca.negotiation.domain.model.offer.PaymentPlan;
import br.com.gestorcobranca.negotiation.domain.model.offer.PlanRequest;
import br.com.gestorcobranca.negotiation.domain.model.offer.PricedDebt;
import br.com.gestorcobranca.negotiation.domain.model.offer.ScheduledPayment;
import br.com.gestorcobranca.negotiation.domain.model.policy.ComponentRates;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.Charges;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.HonorariumBase;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.Installments;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.InterestMode;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.ScheduleFrequency;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Descontos e cronogramas conferidos à mão (contas nos comentários). */
class OfferCalculatorTest {

    private static final LocalDate DUE = LocalDate.of(2026, 1, 10);
    private static final LocalDate REF = LocalDate.of(2026, 3, 11);
    private static final Charges CHARGES = new Charges(new BigDecimal("1"), InterestMode.SIMPLE, new BigDecimal("2"), null,
            List.of(), new BigDecimal("10"), HonorariumBase.UPDATED_DEBT);

    private final OfferCalculator calculator = new OfferCalculator();

    /** 1000 + juros 20 + multa 20 + honorários 104 = 1.144,00 (ver DebtCalculatorTest). */
    private static DebtComposition composition() {
        return new DebtCalculator().calculate(List.of(TitleSnapshot.principalOnly(UUID.randomUUID(), DUE,
                Money.of("1000.00"))), CHARGES, REF, null);
    }

    private static Installments installments(String monthlyRate, ScheduleFrequency frequency, Integer interval) {
        return new Installments(12, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(monthlyRate), frequency,
                interval);
    }

    @Test
    void discountsRecalculateHonorariumOnTheDiscountedBase() {
        // juros e multa 100%: base dos honorários passa a 1000 → 100,00; desconto de 50% nos honorários → 50,00
        // líquido 1000 + 50 = 1.050,00; desconto total 1144 − 1050 = 94,00 (20 + 20 + 54)
        PricedDebt priced = calculator.price(composition(), CHARGES, new ComponentRates(null, null,
                new BigDecimal("100"), new BigDecimal("100"), null, new BigDecimal("50")));
        assertThat(priced.net().total()).isEqualTo(Money.of("1050.00"));
        assertThat(priced.net().honorarium()).isEqualTo(Money.of("50.00"));
        assertThat(priced.discount().honorarium()).isEqualTo(Money.of("54.00"));
        assertThat(priced.discount().total()).isEqualTo(Money.of("94.00"));
    }

    @Test
    void noDiscountKeepsGrossExactly() {
        PricedDebt priced = calculator.price(composition(), CHARGES, ComponentRates.NONE);
        assertThat(priced.net()).isEqualTo(priced.gross());
        assertThat(priced.discount().total()).isEqualTo(Money.ZERO);
        assertThat(priced.lines()).isEmpty();
    }

    @Test
    void partialPercentDiscountIsRoundedHalfEven() {
        // juros 20,00 × 12,5% = 2,50; multa 20,00 × 12,5% = 2,50; honorários sobre 1035 = 103,50
        PricedDebt priced = calculator.price(composition(), CHARGES,
                new ComponentRates(null, null, new BigDecimal("12.5"), new BigDecimal("12.5"), null, null));
        assertThat(priced.net().interest()).isEqualTo(Money.of("17.50"));
        assertThat(priced.net().honorarium()).isEqualTo(Money.of("103.50"));
        assertThat(priced.net().total()).isEqualTo(Money.of("1138.50"));
    }

    @Test
    void cashPaymentIsASinglePayment() {
        PaymentPlan plan = calculator.plan(Money.of("1050.00"), new PlanRequest(1, null, REF),
                installments("2", ScheduleFrequency.MONTHLY, null), Money.ZERO);
        assertThat(plan.schedule()).singleElement().satisfies(p -> {
            assertThat(p.amount()).isEqualTo(Money.of("1050.00"));
            assertThat(p.dueDate()).isEqualTo(REF);
        });
        assertThat(plan.total()).isEqualTo(Money.of("1050.00"));
        assertThat(plan.financingInterest()).isEqualTo(Money.ZERO);
    }

    @Test
    void interestFreeEqualInstallmentsSpreadCentsOnFirstPayments() {
        // 1000,00 / 3 = 333,34 + 333,33 + 333,33; vencimentos mensais no mesmo dia
        PaymentPlan plan = calculator.plan(Money.of("1000.00"), new PlanRequest(3, null, LocalDate.of(2026, 1, 31)),
                installments("0", ScheduleFrequency.MONTHLY, null), Money.ZERO);
        assertThat(plan.schedule()).extracting(ScheduledPayment::amount)
                .containsExactly(Money.of("333.34"), Money.of("333.33"), Money.of("333.33"));
        // 31/01 + 1 mês = 28/02 (ajuste ao fim do mês); + 2 meses = 31/03
        assertThat(plan.schedule()).extracting(ScheduledPayment::dueDate)
                .containsExactly(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31));
        assertThat(plan.total()).isEqualTo(Money.of("1000.00"));
    }

    @Test
    void minimumDownPaymentThenInterestFreeInstallments() {
        // entrada mínima 100,00; 900,00 em 3 × 300,00
        PaymentPlan plan = calculator.plan(Money.of("1000.00"), new PlanRequest(4, null, REF),
                installments("0", ScheduleFrequency.MONTHLY, null), Money.of("100.00"));
        assertThat(plan.downPayment()).isEqualTo(Money.of("100.00"));
        assertThat(plan.schedule()).extracting(ScheduledPayment::amount).containsExactly(Money.of("100.00"),
                Money.of("300.00"), Money.of("300.00"), Money.of("300.00"));
    }

    @Test
    void priceTableAfterDownPayment() {
        // entrada 100; 900 a 2% a.m. em 3: PMT = 900 × 0,02 / (1 − 1,02^−3) = 312,0792 → 312,08
        // 1ª: juros 18,00, amortização 294,08, saldo 605,92
        // 2ª: juros 12,12 (12,1184), amortização 299,96, saldo 305,96
        // 3ª: juros 6,12 (6,1192), amortização 305,96 → parcela 312,08; total 1.036,24
        PaymentPlan plan = calculator.plan(Money.of("1000.00"), new PlanRequest(4, Money.of("100.00"), REF),
                installments("2", ScheduleFrequency.MONTHLY, null), Money.ZERO);
        List<ScheduledPayment> s = plan.schedule();
        assertThat(s).extracting(ScheduledPayment::amount).containsExactly(Money.of("100.00"), Money.of("312.08"),
                Money.of("312.08"), Money.of("312.08"));
        assertThat(s).extracting(ScheduledPayment::interest).containsExactly(Money.ZERO, Money.of("18.00"),
                Money.of("12.12"), Money.of("6.12"));
        assertThat(s.getLast().balanceAfter()).isEqualTo(Money.ZERO);
        assertThat(plan.total()).isEqualTo(Money.of("1036.24"));
        assertThat(plan.financingInterest()).isEqualTo(Money.of("36.24"));
        assertThat(plan.periodRate()).isEqualByComparingTo("2");
    }

    @Test
    void equalPaymentsWithInterestUseAnnuityDue() {
        // 1000 a 2% em 3 iguais (1ª hoje): PMT = 1000 × 0,02 / ((1 − 1,02^−3) × 1,02) = 339,9556 → 339,96
        // saldo 660,04; juros 13,20 (13,2008), amortização 326,76, saldo 333,28;
        // última: juros 6,67 (6,6656) + 333,28 = 339,95 (absorve o centavo); total 1.019,87
        PaymentPlan plan = calculator.plan(Money.of("1000.00"), new PlanRequest(3, null, REF),
                installments("2", ScheduleFrequency.MONTHLY, null), Money.ZERO);
        assertThat(plan.schedule()).extracting(ScheduledPayment::amount).containsExactly(Money.of("339.96"),
                Money.of("339.96"), Money.of("339.95"));
        assertThat(plan.total()).isEqualTo(Money.of("1019.87"));
    }

    @Test
    void everyNDaysUsesEquivalentPeriodRate() {
        // 15 dias a 2% a.m.: (1,02)^(15/30) − 1 = 0,9950494%; datas a cada 15 dias
        PaymentPlan plan = calculator.plan(Money.of("1000.00"), new PlanRequest(3, Money.of("400.00"), REF),
                installments("2", ScheduleFrequency.EVERY_N_DAYS, 15), Money.ZERO);
        assertThat(plan.periodRate()).isEqualByComparingTo("0.9950493836");
        assertThat(plan.schedule()).extracting(ScheduledPayment::dueDate)
                .containsExactly(REF, REF.plusDays(15), REF.plusDays(30));
        // 600 financiados: 1ª parcela com juros 600 × 0,009950494 = 5,97
        assertThat(plan.schedule().get(1).interest()).isEqualTo(Money.of("5.97"));
    }

    @Test
    void downPaymentMustBeBelowNegotiatedAmount() {
        assertThatThrownBy(() -> calculator.plan(Money.of("100.00"), new PlanRequest(2, Money.of("100.00"), REF),
                installments("0", ScheduleFrequency.MONTHLY, null), Money.ZERO))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("entrada");
    }

    @Test
    void minimumDownPaymentRoundsUpToTheCent() {
        // 10% de 1.000,05 = 100,005 → 100,01 (para cima: nunca abaixo do mínimo)
        Installments rules = new Installments(12, BigDecimal.ZERO, new BigDecimal("10"), new BigDecimal("50"),
                BigDecimal.ZERO, ScheduleFrequency.MONTHLY, null);
        assertThat(OfferCalculator.policyMinimumDownPayment(Money.of("1000.05"), rules)).isEqualTo(Money.of("100.01"));
        assertThat(OfferCalculator.policyMinimumDownPayment(Money.of("300.00"), rules)).isEqualTo(Money.of("50.00"));
    }
}
