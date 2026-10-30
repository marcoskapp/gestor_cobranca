package br.com.gestorcobranca.publicapi.domain;

import br.com.gestorcobranca.publicapi.domain.model.BillableUsage;
import br.com.gestorcobranca.publicapi.domain.model.PriceTerms;
import br.com.gestorcobranca.publicapi.domain.model.Proration;
import br.com.gestorcobranca.publicapi.domain.service.BillingCycles;
import br.com.gestorcobranca.publicapi.domain.service.BillingCycles.Cycle;
import br.com.gestorcobranca.publicapi.domain.service.PriceCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Mensalidade e franquias proporcionais no ciclo de transição (decisão 621). */
class ProrationTest {

    private static PriceTerms terms(String fee, long includedRequests) {
        return new PriceTerms(new BigDecimal(fee), includedRequests, new BigDecimal("0.01"), 10, BigDecimal.ONE, 10,
                BigDecimal.ONE, 0, BigDecimal.ZERO);
    }

    @Test
    void regularCyclesHaveFactorOneWhateverTheMonthLength() {
        assertThat(BillingCycles.proration(BillingCycles.startingAt(LocalDate.of(2026, 2, 1), 31)))
                .isEqualTo(new Proration(28, 28));
        assertThat(BillingCycles.proration(BillingCycles.startingAt(LocalDate.of(2026, 3, 11), 10)))
                .satisfies(p -> assertThat(p.isFull()).isTrue());
        PriceCalculator.Result r = PriceCalculator.calculate(terms("100.00", 1000), BillableUsage.ZERO,
                BillingCycles.proration(BillingCycles.startingAt(LocalDate.of(2026, 1, 1), 31)));
        assertThat(r.total()).isEqualByComparingTo("100.00");
        assertThat(r.lines().get(1).included()).isEqualTo(1000);
    }

    @Test
    void shortTransitionCycleProratesFeeAndAllowances() {
        // último ciclo fechou em 10/03 (dia 10); o contrato passou a fechar no dia 20: ciclo de transição 11/03 a 20/03
        Cycle transition = BillingCycles.startingAt(LocalDate.of(2026, 3, 11), 20);
        assertThat(transition.end()).isEqualTo(LocalDate.of(2026, 3, 20));
        Proration p = BillingCycles.proration(transition);
        // referência: ciclo regular que termina em 20/03 com o dia 20 = 21/02 a 20/03 (28 dias)
        assertThat(p).isEqualTo(new Proration(10, 28));
        PriceCalculator.Result r = PriceCalculator.calculate(terms("100.00", 1000), new BillableUsage(400, 0, 0, 0), p);
        // 100,00 × 10 ÷ 28 = 35,714… → 35,71
        assertThat(r.lines().getFirst().amount()).isEqualByComparingTo("35.71");
        assertThat(r.lines().getFirst().unitPrice()).isEqualByComparingTo("100.00");
        // franquia 1.000 × 10 ÷ 28 = 357,14… → 358 (para cima); 400 − 358 = 42 excedentes × 0,01
        assertThat(r.lines().get(1).included()).isEqualTo(358);
        assertThat(r.lines().get(1).billable()).isEqualTo(42);
        assertThat(r.lines().get(1).amount()).isEqualByComparingTo("0.42");
        assertThat(r.total()).isEqualByComparingTo("36.13");
    }

    @Test
    void longCycleProratesAboveOne() {
        Proration p = new Proration(41, 31);
        assertThat(p.money(new BigDecimal("31.00"))).isEqualByComparingTo("41.00");
        assertThat(p.allowance(31)).isEqualTo(41);
        assertThat(p.allowance(10)).isEqualTo(14); // 13,2… → 14
        PriceCalculator.Result r = PriceCalculator.calculate(terms("62.00", 0), BillableUsage.ZERO, p);
        assertThat(r.total()).isEqualByComparingTo("82.00");
    }

    @Test
    void centsAreRoundedHalfEvenInASingleOperation() {
        Proration quarter = new Proration(5, 20);
        // 0,10 × 5 ÷ 20 = 0,025 → 0,02 (HALF_EVEN; HALF_UP daria 0,03)
        assertThat(quarter.money(new BigDecimal("0.10"))).isEqualByComparingTo("0.02");
        // 0,30 × 5 ÷ 20 = 0,075 → 0,08
        assertThat(quarter.money(new BigDecimal("0.30"))).isEqualByComparingTo("0.08");
        // sem arredondar o fator antes: 100,00 × 1 ÷ 3 = 33,33 (e não 100 × 0,33 = 33,00)
        assertThat(new Proration(1, 3).money(new BigDecimal("100.00"))).isEqualByComparingTo("33.33");
        assertThat(new Proration(2, 3).money(new BigDecimal("100.00"))).isEqualByComparingTo("66.67");
        assertThat(Proration.FULL.money(new BigDecimal("12.345"))).isEqualByComparingTo("12.34");
    }
}
