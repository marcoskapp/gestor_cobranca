package br.com.gestorcobranca.publicapi.domain.platform;

import br.com.gestorcobranca.publicapi.domain.model.BillableUsage;
import br.com.gestorcobranca.publicapi.domain.model.PriceTerms;
import br.com.gestorcobranca.publicapi.domain.model.Proration;
import br.com.gestorcobranca.publicapi.domain.model.UsageTotals;
import br.com.gestorcobranca.publicapi.domain.model.platform.PlatformPriceTerms;
import br.com.gestorcobranca.publicapi.domain.model.platform.VolumeTier;
import br.com.gestorcobranca.publicapi.domain.service.BillingCycles;
import br.com.gestorcobranca.publicapi.domain.service.PriceCalculator;
import br.com.gestorcobranca.publicapi.domain.service.platform.PlatformPriceCalculator;
import br.com.gestorcobranca.publicapi.domain.service.platform.PlatformPriceCalculator.Line;
import br.com.gestorcobranca.publicapi.domain.service.platform.PlatformPriceCalculator.Result;
import br.com.gestorcobranca.publicapi.domain.service.platform.PlatformPriceCalculator.TierCharge;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Cálculo do extrato da plataforma: franquias, excedente, faixas de volume, MB e proporcionalidade. */
class PlatformPriceCalculatorTest {

    private static final long MB = 1_048_576L;

    private static PlatformPriceTerms flat() {
        return new PlatformPriceTerms(10, new BigDecimal("500.00"), 10_000, new BigDecimal("0.010000"), List.of(), 100,
                new BigDecimal("0.50"), 200, new BigDecimal("0.25"), 1_000, new BigDecimal("0.02"));
    }

    private static PlatformPriceTerms tiered() {
        return new PlatformPriceTerms(10, new BigDecimal("500.00"), 10_000, null, List.of(
                new VolumeTier(100_000L, new BigDecimal("0.01")), new VolumeTier(1_000_000L, new BigDecimal("0.008")),
                new VolumeTier(null, new BigDecimal("0.005"))), 0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0,
                BigDecimal.ZERO);
    }

    @Test
    void chargesOnlyAboveTheAllowancesAndTheMonthlyFeeAlways() {
        Result r = PlatformPriceCalculator.calculate(flat(), new BillableUsage(12_500, 103, 150, 1_000), Proration.FULL);
        assertThat(r.lines()).extracting(Line::metric).containsExactly("MONTHLY_FEE", "REQUESTS", "MB_IN", "MB_OUT",
                "RECORDS");
        assertThat(r.lines().get(0).amount()).isEqualByComparingTo("500.00");
        assertThat(r.lines().get(1).billable()).isEqualTo(2_500);
        assertThat(r.lines().get(1).amount()).isEqualByComparingTo("25.00");
        assertThat(r.lines().get(1).tiers()).isEmpty();
        assertThat(r.lines().get(2).billable()).isEqualTo(3);
        assertThat(r.lines().get(2).amount()).isEqualByComparingTo("1.50");
        // dentro da franquia: nada a cobrar
        assertThat(r.lines().get(3).billable()).isZero();
        assertThat(r.lines().get(4).amount()).isEqualByComparingTo("0.00");
        assertThat(r.total()).isEqualByComparingTo("526.50");
        // sem consumo: só a mensalidade
        assertThat(PlatformPriceCalculator.calculate(flat(), BillableUsage.ZERO, Proration.FULL).total())
                .isEqualByComparingTo("500.00");
    }

    @Test
    void graduatedVolumeTiersChargeEachExcessCallAtTheTierItFallsIn() {
        // franquia 10.000; 150.000 chamadas = 90.000 × 0,01 + 50.000 × 0,008 = 900 + 400
        Line requests = PlatformPriceCalculator.calculate(tiered(), new BillableUsage(150_000, 0, 0, 0), Proration.FULL)
                .lines().get(1);
        assertThat(requests.unitPrice()).isNull();
        assertThat(requests.billable()).isEqualTo(140_000);
        assertThat(requests.tiers()).extracting(TierCharge::units).containsExactly(90_000L, 50_000L, 0L);
        assertThat(requests.tiers()).extracting(TierCharge::from).containsExactly(1L, 100_001L, 1_000_001L);
        assertThat(requests.tiers().get(2).to()).isNull();
        assertThat(requests.amount()).isEqualByComparingTo("1300.00");

        // atravessando todas as faixas: 90.000 × 0,01 + 900.000 × 0,008 + 500.000 × 0,005
        Line all = PlatformPriceCalculator.calculate(tiered(), new BillableUsage(1_500_000, 0, 0, 0), Proration.FULL)
                .lines().get(1);
        assertThat(all.amount()).isEqualByComparingTo("10600.00");
        assertThat(all.tiers().stream().mapToLong(TierCharge::units).sum()).isEqualTo(all.billable());

        // dentro da franquia: nenhuma faixa cobra
        Line within = PlatformPriceCalculator.calculate(tiered(), new BillableUsage(9_000, 0, 0, 0), Proration.FULL)
                .lines().get(1);
        assertThat(within.amount()).isEqualByComparingTo("0.00");
        assertThat(within.tiers()).allSatisfy(t -> assertThat(t.units()).isZero());
    }

    @Test
    void franchiseLargerThanTheFirstTierSkipsItEntirely() {
        PlatformPriceTerms terms = new PlatformPriceTerms(1, BigDecimal.ZERO, 150_000, null, List.of(
                new VolumeTier(100_000L, new BigDecimal("0.01")), new VolumeTier(null, new BigDecimal("0.008"))),
                0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO);
        Line requests = PlatformPriceCalculator.calculate(terms, new BillableUsage(200_000, 0, 0, 0), Proration.FULL)
                .lines().get(1);
        assertThat(requests.tiers()).extracting(TierCharge::units).containsExactly(0L, 50_000L);
        assertThat(requests.amount()).isEqualByComparingTo("400.00");
    }

    @Test
    void eachTierIsRoundedToCentsWithHalfEvenAndTheLineIsTheirSum() {
        PlatformPriceTerms terms = new PlatformPriceTerms(1, BigDecimal.ZERO, 0, null, List.of(
                new VolumeTier(3L, new BigDecimal("0.001500")), new VolumeTier(null, new BigDecimal("0.002500"))),
                0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO);
        // 3 × 0,0015 = 0,0045 → 0,00; 3 × 0,0025 = 0,0075 → 0,01 (HALF_EVEN)
        Line l = PlatformPriceCalculator.calculate(terms, new BillableUsage(6, 0, 0, 0), Proration.FULL).lines().get(1);
        assertThat(l.tiers()).extracting(TierCharge::amount).containsExactly(new BigDecimal("0.00"),
                new BigDecimal("0.01"));
        assertThat(l.amount()).isEqualByComparingTo("0.01");
    }

    @Test
    void transitionCycleProratesFeeAllowancesAndTierLimitsRoundingUp() {
        // ciclo de transição de 10 dias num ciclo de referência de 28 (mudança do fechamento do dia 10 para o 20)
        Proration p = BillingCycles.proration(new BillingCycles.Cycle(LocalDate.of(2026, 3, 11),
                LocalDate.of(2026, 3, 20), 20));
        assertThat(p).isEqualTo(new Proration(10, 28));
        Result r = PlatformPriceCalculator.calculate(tiered(), new BillableUsage(50_000, 0, 0, 0), p);
        assertThat(r.lines().get(0).amount()).isEqualByComparingTo("178.57");
        Line requests = r.lines().get(1);
        // franquia 10.000 × 10/28 = 3.571,4 → 3.572; limite 100.000 × 10/28 = 35.714,3 → 35.715
        assertThat(requests.included()).isEqualTo(3_572);
        assertThat(requests.tiers().get(0).to()).isEqualTo(35_715L);
        assertThat(requests.tiers()).extracting(TierCharge::units).containsExactly(32_143L, 14_285L, 0L);
        assertThat(requests.amount()).isEqualByComparingTo("435.71");
        assertThat(r.total()).isEqualByComparingTo("614.28");
    }

    @Test
    void sameRulesAsTheCreditorBillingProduceTheSameFlatAmounts() {
        // mesma tabela nas duas camadas: os valores das linhas coincidem (cálculo compartilhado)
        PriceTerms creditor = new PriceTerms(new BigDecimal("500.00"), 10_000, new BigDecimal("0.010000"), 100,
                new BigDecimal("0.50"), 200, new BigDecimal("0.25"), 1_000, new BigDecimal("0.02"));
        BillableUsage usage = new BillableUsage(12_345, 321, 222, 7_777);
        Proration p = new Proration(17, 31);
        PriceCalculator.Result c = PriceCalculator.calculate(creditor, usage, p);
        Result platform = PlatformPriceCalculator.calculate(flat(), usage, p);
        assertThat(platform.lines()).extracting(Line::amount)
                .containsExactlyElementsOf(c.lines().stream().map(PriceCalculator.Line::amount).toList());
        assertThat(platform.total()).isEqualByComparingTo(c.total());
    }

    @Test
    void tenantQuantitiesAreTheSumOfEveryTokenRoundedPerTokenAndDirection() {
        // três tokens (dois credores, um revogado no meio do ciclo): 1 byte, 1 MB + 1 byte e nada recebido
        UsageTotals a = new UsageTotals(10, 0, 2, 0, 100, 100, 1, 1, 0);
        UsageTotals b = new UsageTotals(5, 1, 0, 1, 2 * MB, MB, MB + 1, MB, 3);
        UsageTotals c = new UsageTotals(1, 0, 0, 0, 0, 0, 0, 0, 0);
        BillableUsage sum = BillableUsage.sumOfTokens(List.of(a, b, c));
        assertThat(sum).isEqualTo(new BillableUsage(8 + 3 + 1, 1 + 2, 1 + 1, 3));
    }
}
