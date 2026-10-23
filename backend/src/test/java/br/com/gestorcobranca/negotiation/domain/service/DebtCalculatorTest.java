package br.com.gestorcobranca.negotiation.domain.service;

import br.com.gestorcobranca.negotiation.domain.model.calculation.CalculationLine.Rule;
import br.com.gestorcobranca.negotiation.domain.model.calculation.DebtComposition;
import br.com.gestorcobranca.negotiation.domain.model.calculation.IndexSeries;
import br.com.gestorcobranca.negotiation.domain.model.calculation.TitleComposition;
import br.com.gestorcobranca.negotiation.domain.model.calculation.TitleSnapshot;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.Charges;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.Fee;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.FeeType;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.HonorariumBase;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.InterestMode;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Casos numéricos conferidos à mão (contas no comentário de cada teste). Vencimento 10/01/2026 e
 * referência 11/03/2026 = 21 dias (11–31/jan) + 28 (fev) + 11 (1–11/mar) = 60 dias de atraso.
 */
class DebtCalculatorTest {

    private static final LocalDate DUE = LocalDate.of(2026, 1, 10);
    private static final LocalDate REF = LocalDate.of(2026, 3, 11);
    private final DebtCalculator calculator = new DebtCalculator();

    private static Charges charges(String interest, InterestMode mode, String fine, String index, String honorarium,
                                   HonorariumBase base, Fee... fees) {
        return new Charges(new BigDecimal(interest), mode, new BigDecimal(fine), index, List.of(fees),
                new BigDecimal(honorarium), base);
    }

    private static TitleSnapshot title(String principal, LocalDate due) {
        return TitleSnapshot.principalOnly(UUID.randomUUID(), due, Money.of(principal));
    }

    private TitleComposition single(TitleSnapshot t, Charges c, LocalDate ref, IndexSeries index) {
        return calculator.calculate(List.of(t), c, ref, index).titles().getFirst();
    }

    @Test
    void simpleInterestProRataDieFineAndHonorariumOnUpdatedDebt() {
        // juros 1000 × 1% × 60/30 = 20,00; multa 1000 × 2% = 20,00;
        // honorários (1000 + 20 + 20) × 10% = 104,00; total 1.144,00
        TitleComposition c = single(title("1000.00", DUE),
                charges("1", InterestMode.SIMPLE, "2", null, "10", HonorariumBase.UPDATED_DEBT), REF, null);
        assertThat(c.daysOverdue()).isEqualTo(60);
        assertThat(c.gross().interest()).isEqualTo(Money.of("20.00"));
        assertThat(c.gross().fine()).isEqualTo(Money.of("20.00"));
        assertThat(c.gross().correction()).isEqualTo(Money.ZERO);
        assertThat(c.gross().honorarium()).isEqualTo(Money.of("104.00"));
        assertThat(c.total()).isEqualTo(Money.of("1144.00"));
        assertThat(c.lines()).extracting(l -> l.rule())
                .containsExactly(Rule.PRINCIPAL_BALANCE, Rule.INTEREST_SIMPLE, Rule.FINE, Rule.HONORARIUM);
    }

    @Test
    void compoundInterestWithFractionalMonths() {
        // 45 dias = 1,5 mês: 1,01^1,5 = 1,0150374377…; juros 1000 × 0,0150374377 = 15,037 → 15,04
        TitleComposition c = single(title("1000.00", DUE),
                charges("1", InterestMode.COMPOUND, "0", null, "0", HonorariumBase.UPDATED_DEBT), DUE.plusDays(45), null);
        assertThat(c.gross().interest()).isEqualTo(Money.of("15.04"));
        assertThat(c.lines().get(1).factor()).isEqualByComparingTo("0.0150374377");
    }

    @Test
    void compoundEqualsSimpleOverExactlyOneMonthAndExceedsItAfterwards() {
        // 30 dias: 1,01^1 − 1 = 1% nos dois regimes; 60 dias: composto 1000 × (1,0201 − 1) = 20,10 > 20,00
        Charges compound = charges("1", InterestMode.COMPOUND, "0", null, "0", HonorariumBase.PRINCIPAL);
        assertThat(single(title("1000.00", DUE), compound, DUE.plusDays(30), null).gross().interest())
                .isEqualTo(Money.of("10.00"));
        assertThat(single(title("1000.00", DUE), compound, REF, null).gross().interest()).isEqualTo(Money.of("20.10"));
    }

    @Test
    void monetaryCorrectionIsCompoundProRataDieByMonth() {
        // jan 0,50% (21/31 dias), fev 0,30% (28/28), mar 0,40% (11/31):
        // fator = 1,005^(21/31) × 1,003 × 1,004^(11/31) ≈ e^(0,0033787 + 0,0029955 + 0,0014165) ≈ 1,0078211
        // correção 1000 × 0,0078211 = 7,82; base corrigida 1.007,82
        // juros 1.007,82 × 1% × 2 = 20,1564 → 20,16; multa 2% = 20,16
        // honorários (1.007,82 + 20,16 + 20,16) × 10% = 104,814 → 104,81; total 1.152,95
        IndexSeries ipca = new IndexSeries("IPCA", Map.of(YearMonth.of(2026, 1), new BigDecimal("0.50"),
                YearMonth.of(2026, 2), new BigDecimal("0.30"), YearMonth.of(2026, 3), new BigDecimal("0.40")));
        TitleComposition c = single(title("1000.00", DUE),
                charges("1", InterestMode.SIMPLE, "2", "IPCA", "10", HonorariumBase.UPDATED_DEBT), REF, ipca);
        assertThat(c.correctionFactor().doubleValue()).isCloseTo(1.0078211, within(1e-7));
        assertThat(c.gross().correction()).isEqualTo(Money.of("7.82"));
        assertThat(c.gross().interest()).isEqualTo(Money.of("20.16"));
        assertThat(c.gross().fine()).isEqualTo(Money.of("20.16"));
        assertThat(c.gross().honorarium()).isEqualTo(Money.of("104.81"));
        assertThat(c.total()).isEqualTo(Money.of("1152.95"));
    }

    @Test
    void unpublishedTrailingMonthCountsAsZeroWithNote() {
        // abril sem variação publicada (última = março): só jan–mar contam; aviso na composição
        IndexSeries ipca = new IndexSeries("IPCA", Map.of(YearMonth.of(2026, 1), new BigDecimal("0.50"),
                YearMonth.of(2026, 2), new BigDecimal("0.30"), YearMonth.of(2026, 3), new BigDecimal("0.40")));
        DebtComposition c = calculator.calculate(List.of(title("1000.00", DUE)),
                charges("0", InterestMode.SIMPLE, "0", "IPCA", "0", HonorariumBase.PRINCIPAL), LocalDate.of(2026, 4, 15),
                ipca);
        // fator = 1,005^(21/31) × 1,003 × 1,004 (março inteiro) ≈ 1,010422 → 10,42
        assertThat(c.total().correction()).isEqualTo(Money.of("10.42"));
        assertThat(c.notes()).singleElement().asString().contains("2026-04");
    }

    @Test
    void gapBeforeLastPublishedMonthIsAnError() {
        IndexSeries gap = new IndexSeries("IPCA", Map.of(YearMonth.of(2026, 1), new BigDecimal("0.50"),
                YearMonth.of(2026, 3), new BigDecimal("0.40")));
        assertThatThrownBy(() -> calculator.calculate(List.of(title("1000.00", DUE)),
                charges("0", InterestMode.SIMPLE, "0", "IPCA", "0", HonorariumBase.PRINCIPAL), REF, gap))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("2026-02");
    }

    @Test
    void deflationDoesNotReducePrincipal() {
        IndexSeries igpm = new IndexSeries("IGPM", Map.of(YearMonth.of(2026, 1), new BigDecimal("-1.00"),
                YearMonth.of(2026, 2), new BigDecimal("-0.50"), YearMonth.of(2026, 3), new BigDecimal("0.10")));
        TitleComposition c = single(title("1000.00", DUE),
                charges("0", InterestMode.SIMPLE, "0", "IGPM", "0", HonorariumBase.PRINCIPAL), REF, igpm);
        assertThat(c.gross().correction()).isEqualTo(Money.ZERO);
        assertThat(c.correctionFactor()).isLessThan(BigDecimal.ONE);
        assertThat(c.lines().get(1).note()).contains("Deflação");
    }

    @Test
    void roundingIsBankersHalfEvenPerComponent() {
        // multa 10,25 × 10% = 1,025 → 1,02 (HALF_EVEN; HALF_UP daria 1,03)
        // multa 10,35 × 10% = 1,035 → 1,04 (empate arredonda para o par)
        Charges fineOnly = charges("0", InterestMode.SIMPLE, "10", null, "0", HonorariumBase.PRINCIPAL);
        assertThat(single(title("10.25", DUE), fineOnly, REF, null).gross().fine()).isEqualTo(Money.of("1.02"));
        assertThat(single(title("10.35", DUE), fineOnly, REF, null).gross().fine()).isEqualTo(Money.of("1.04"));
    }

    @Test
    void totalsAreSumsOfRoundedTitleValues() {
        // dois títulos de 10,25 com multa 10%: 1,02 + 1,02 = 2,04 (e não 20,50 × 10% = 2,05)
        Charges fineOnly = charges("0", InterestMode.SIMPLE, "10", null, "0", HonorariumBase.PRINCIPAL);
        DebtComposition c = calculator.calculate(List.of(title("10.25", DUE), title("10.25", DUE.plusDays(5))), fineOnly,
                REF, null);
        assertThat(c.total().fine()).isEqualTo(Money.of("2.04"));
        assertThat(c.total().principal()).isEqualTo(Money.of("20.50"));
        assertThat(c.maxDaysOverdue()).isEqualTo(60);
    }

    @Test
    void notOverdueTitlesHaveNoCharges() {
        TitleComposition c = single(title("500.00", REF.plusDays(3)),
                charges("1", InterestMode.SIMPLE, "2", null, "10", HonorariumBase.PRINCIPAL), REF, null);
        assertThat(c.daysOverdue()).isZero();
        assertThat(c.gross().interest()).isEqualTo(Money.ZERO);
        assertThat(c.gross().fine()).isEqualTo(Money.ZERO);
        // honorários incidem sobre o principal em cobrança mesmo sem atraso: 500 × 10% = 50
        assertThat(c.gross().honorarium()).isEqualTo(Money.of("50.00"));
    }

    @Test
    void authorizedFeesFixedAndPercentOfPrincipal() {
        // tarifa fixa 15,00 + 1,5% de 1000 = 15,00 → encargos 30,00
        TitleComposition c = single(title("1000.00", DUE), charges("0", InterestMode.SIMPLE, "0", null, "0",
                HonorariumBase.PRINCIPAL, new Fee("TARIFA", "Tarifa de cobrança", FeeType.FIXED, new BigDecimal("15")),
                new Fee("EMISSAO", "Emissão", FeeType.PERCENT_OF_PRINCIPAL, new BigDecimal("1.5"))), REF, null);
        assertThat(c.gross().fees()).isEqualTo(Money.of("30.00"));
    }

    @Test
    void recognizedChargesAreCarriedAndEnterTheHonorariumBase() {
        // principal 800 + juros reconhecidos 50 + honorários reconhecidos 5, sem mora nova (taxas 0):
        // honorários calculados 10% × (800 + 50) = 85,00 → honorários 90,00; total 940,00
        TitleSnapshot t = new TitleSnapshot(UUID.randomUUID(), null, DUE, Money.of("800.00"), Money.of("50.00"), null,
                null, Money.of("5.00"), Money.of("200.00"));
        TitleComposition c = single(t, charges("0", InterestMode.SIMPLE, "0", null, "10", HonorariumBase.UPDATED_DEBT),
                REF, null);
        assertThat(c.gross().interest()).isEqualTo(Money.of("50.00"));
        assertThat(c.gross().honorarium()).isEqualTo(Money.of("90.00"));
        assertThat(c.total()).isEqualTo(Money.of("940.00"));
        assertThat(c.paidTotal()).isEqualTo(Money.of("200.00"));
        assertThat(c.lines()).extracting(l -> l.rule()).contains(Rule.RECOGNIZED_CHARGE);
    }

    @Test
    void honorariumBaseOptions() {
        // principal 1000, juros 20, multa 20 (60 dias): base PRINCIPAL → 100,00; UPDATED_DEBT → 104,00
        assertThat(single(title("1000.00", DUE), charges("1", InterestMode.SIMPLE, "2", null, "10",
                HonorariumBase.PRINCIPAL), REF, null).gross().honorarium()).isEqualTo(Money.of("100.00"));
        assertThat(single(title("1000.00", DUE), charges("1", InterestMode.SIMPLE, "2", null, "10",
                HonorariumBase.UPDATED_PRINCIPAL), REF, null).gross().honorarium()).isEqualTo(Money.of("100.00"));
    }

    @Test
    void correctionWithoutSeriesIsAProgrammingError() {
        assertThatThrownBy(() -> calculator.calculate(List.of(title("1.00", DUE)),
                charges("0", InterestMode.SIMPLE, "0", "IPCA", "0", HonorariumBase.PRINCIPAL), REF, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
