package br.com.gestorcobranca.settlement.domain.service;

import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules.HonorariumPolicy;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules.RateRule;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules.ReceiptProfile;
import br.com.gestorcobranca.settlement.domain.model.rules.HonorariumOwnership;
import br.com.gestorcobranca.settlement.domain.model.rules.PaymentType;
import br.com.gestorcobranca.settlement.domain.model.rules.ReceivedBy;
import br.com.gestorcobranca.settlement.domain.service.CommissionCalculator.Input;
import br.com.gestorcobranca.settlement.domain.service.CommissionCalculator.Rating;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Casos conferidos à mão (centavos HALF_EVEN). */
class CommissionCalculatorTest {

    private static final UUID PORTFOLIO = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    /** Faixas de atraso: até 90 dias 8%, 91–180 12%, acima de 180 18%; padrão 10%; R$ 25,00 por acordo. */
    private static CommissionRules bands() {
        return new CommissionRules(new BigDecimal("10"), List.of(
                rule("ATE_90", 0, null, null, 90, null, null, null, "8"),
                rule("D91_180", 0, null, 91, 180, null, null, null, "12"),
                rule("ACIMA_180", 0, null, 181, null, null, null, null, "18")),
                new BigDecimal("25.00"), HonorariumPolicy.agencyFull(), true).validated();
    }

    private static RateRule rule(String code, int priority, UUID portfolio, Integer min, Integer max, PaymentType type,
                                 ReceivedBy receivedBy, Boolean renegotiation, String rate) {
        return new RateRule(code, null, priority, portfolio, min, max, type, receivedBy, renegotiation,
                new BigDecimal(rate));
    }

    private static ReceiptProfile profile(Integer dpd, PaymentType type, ReceivedBy by) {
        return new ReceiptProfile(PORTFOLIO, dpd, type, by, false);
    }

    private static Rating rate(CommissionRules rules, String amount, String honorarium, ReceiptProfile p, boolean fee) {
        return CommissionCalculator.rate(rules, new Input(Money.of(amount), Money.of(honorarium), p, fee));
    }

    @Test
    void defaultRateOverRecoveredAmount() {
        CommissionRules rules = new CommissionRules(new BigDecimal("15"), List.of(), null,
                HonorariumPolicy.agencyFull(), false).validated();
        Rating r = rate(rules, "1000.00", "0", profile(30, PaymentType.LUMP_SUM, ReceivedBy.AGENCY), true);
        // 1.000,00 × 15% = 150,00; repasse = 1.000,00 − 150,00 = 850,00
        assertThat(r.ruleCode()).isEqualTo("PADRAO");
        assertThat(r.commission()).isEqualTo(Money.of("150.00"));
        assertThat(r.fixedFee()).isEqualTo(Money.ZERO);
        assertThat(r.transferEffect()).isEqualTo(Money.of("850.00"));
    }

    @Test
    void delayBandHonorariumAndFixedFeeOnFirstReceipt() {
        Rating r = rate(bands(), "1100.00", "100.00", profile(120, PaymentType.INSTALLMENTS, ReceivedBy.AGENCY), true);
        // honorários 100,00 são da assessoria (100%); base = 1.100,00 − 100,00 = 1.000,00
        // faixa 91–180: 1.000,00 × 12% = 120,00; valor fixo 25,00
        // repasse = 1.100,00 − 100,00 − 120,00 − 25,00 = 855,00
        assertThat(r.ruleCode()).isEqualTo("D91_180");
        assertThat(r.agencyHonorarium()).isEqualTo(Money.of("100.00"));
        assertThat(r.creditorHonorarium()).isEqualTo(Money.ZERO);
        assertThat(r.commissionBase()).isEqualTo(Money.of("1000.00"));
        assertThat(r.commission()).isEqualTo(Money.of("120.00"));
        assertThat(r.fixedFee()).isEqualTo(Money.of("25.00"));
        assertThat(r.transferEffect()).isEqualTo(Money.of("855.00"));
        assertThat(r.agencyRevenue()).isEqualTo(Money.of("245.00"));
        assertThat(r.memory()).extracting(CommissionCalculator.MemoryLine::step)
                .containsExactly("RECOVERED", "AGENCY_HONORARIUM", "CREDITOR_HONORARIUM", "COMMISSION_BASE",
                        "COMMISSION", "FIXED_FEE", "TRANSFER_EFFECT");
    }

    @Test
    void bandsAreInclusiveAndUnknownDelayFallsBackToDefault() {
        CommissionRules rules = bands();
        assertThat(rate(rules, "100.00", "0", profile(90, PaymentType.LUMP_SUM, ReceivedBy.AGENCY), false).commission())
                .isEqualTo(Money.of("8.00"));
        assertThat(rate(rules, "100.00", "0", profile(91, PaymentType.LUMP_SUM, ReceivedBy.AGENCY), false).commission())
                .isEqualTo(Money.of("12.00"));
        assertThat(rate(rules, "100.00", "0", profile(181, PaymentType.LUMP_SUM, ReceivedBy.AGENCY), false).commission())
                .isEqualTo(Money.of("18.00"));
        Rating unknown = rate(rules, "100.00", "0", profile(null, PaymentType.LUMP_SUM, ReceivedBy.AGENCY), false);
        assertThat(unknown.ruleCode()).isEqualTo("PADRAO");
        assertThat(unknown.commission()).isEqualTo(Money.of("10.00"));
    }

    @Test
    void lumpSumVersusInstallmentsAndPriority() {
        CommissionRules rules = new CommissionRules(new BigDecimal("10"), List.of(
                rule("AVISTA", 0, null, null, null, PaymentType.LUMP_SUM, null, null, "15"),
                rule("PARCELADO", 0, null, null, null, PaymentType.INSTALLMENTS, null, null, "20"),
                rule("CARTEIRA_ESPECIAL", 10, PORTFOLIO, null, null, null, null, null, "5")),
                null, HonorariumPolicy.agencyFull(), false).validated();
        ReceiptProfile other = new ReceiptProfile(UUID.randomUUID(), 10, PaymentType.LUMP_SUM, ReceivedBy.AGENCY, false);
        assertThat(rate(rules, "200.00", "0", other, false).commission()).isEqualTo(Money.of("30.00"));
        ReceiptProfile otherParcelado = new ReceiptProfile(UUID.randomUUID(), 10, PaymentType.INSTALLMENTS,
                ReceivedBy.AGENCY, false);
        assertThat(rate(rules, "200.00", "0", otherParcelado, false).commission()).isEqualTo(Money.of("40.00"));
        // regra específica da carteira tem prioridade maior: 200,00 × 5% = 10,00
        Rating special = rate(rules, "200.00", "0", profile(10, PaymentType.INSTALLMENTS, ReceivedBy.AGENCY), false);
        assertThat(special.ruleCode()).isEqualTo("CARTEIRA_ESPECIAL");
        assertThat(special.commission()).isEqualTo(Money.of("10.00"));
    }

    @Test
    void samepriorityPrefersMostSpecificRule() {
        CommissionRules rules = new CommissionRules(new BigDecimal("10"), List.of(
                rule("FAIXA", 0, null, 0, 365, null, null, null, "12"),
                rule("FAIXA_CARTEIRA", 0, PORTFOLIO, 0, 365, null, null, null, "9"),
                rule("RENEGOCIACAO", 0, null, null, null, null, null, true, "6")),
                null, HonorariumPolicy.agencyFull(), false).validated();
        assertThat(rate(rules, "100.00", "0", profile(40, PaymentType.LUMP_SUM, ReceivedBy.AGENCY), false).ruleCode())
                .isEqualTo("FAIXA_CARTEIRA");
        ReceiptProfile renegotiation = new ReceiptProfile(null, null, PaymentType.INSTALLMENTS, ReceivedBy.AGENCY, true);
        assertThat(rate(rules, "100.00", "0", renegotiation, false).commission()).isEqualTo(Money.of("6.00"));
    }

    @Test
    void honorariumOwnedByCreditorEntersTheBase() {
        CommissionRules rules = new CommissionRules(new BigDecimal("10"), List.of(), null,
                new HonorariumPolicy(HonorariumOwnership.CREDITOR, null), false).validated();
        Rating r = rate(rules, "1100.00", "100.00", profile(30, PaymentType.LUMP_SUM, ReceivedBy.AGENCY), false);
        // honorários repassados: base = 1.100,00; comissão 110,00; repasse = 1.100,00 − 110,00 = 990,00
        assertThat(r.agencyHonorarium()).isEqualTo(Money.ZERO);
        assertThat(r.creditorHonorarium()).isEqualTo(Money.of("100.00"));
        assertThat(r.commission()).isEqualTo(Money.of("110.00"));
        assertThat(r.transferEffect()).isEqualTo(Money.of("990.00"));
    }

    @Test
    void agencyShareOfHonorarium() {
        CommissionRules rules = new CommissionRules(new BigDecimal("10"), List.of(), null,
                new HonorariumPolicy(HonorariumOwnership.AGENCY, new BigDecimal("50")), false).validated();
        Rating r = rate(rules, "1100.00", "100.00", profile(30, PaymentType.LUMP_SUM, ReceivedBy.AGENCY), false);
        // assessoria 50,00, credor 50,00; base 1.050,00 × 10% = 105,00; repasse = 1.100,00 − 50,00 − 105,00 = 945,00
        assertThat(r.agencyHonorarium()).isEqualTo(Money.of("50.00"));
        assertThat(r.creditorHonorarium()).isEqualTo(Money.of("50.00"));
        assertThat(r.commission()).isEqualTo(Money.of("105.00"));
        assertThat(r.transferEffect()).isEqualTo(Money.of("945.00"));
    }

    @Test
    void paymentReceivedByCreditorMakesTheCreditorOweTheAgency() {
        Rating r = rate(bands(), "1100.00", "100.00", profile(30, PaymentType.LUMP_SUM, ReceivedBy.CREDITOR), true);
        // base 1.000,00 × 8% = 80,00; o credor recebeu: deve 100,00 + 80,00 + 25,00 = 205,00
        assertThat(r.commission()).isEqualTo(Money.of("80.00"));
        assertThat(r.transferEffect()).isEqualTo(Money.of("-205.00"));
    }

    @Test
    void centsAreRoundedHalfEven() {
        CommissionRules rules = new CommissionRules(new BigDecimal("10"), List.of(), null,
                HonorariumPolicy.agencyFull(), false).validated();
        // 100,25 × 10% = 10,025 → 10,02 (meio para o par)
        assertThat(rate(rules, "100.25", "0", profile(1, PaymentType.LUMP_SUM, ReceivedBy.AGENCY), false).commission())
                .isEqualTo(Money.of("10.02"));
        // 100,35 × 10% = 10,035 → 10,04
        assertThat(rate(rules, "100.35", "0", profile(1, PaymentType.LUMP_SUM, ReceivedBy.AGENCY), false).commission())
                .isEqualTo(Money.of("10.04"));
    }

    @Test
    void partialThenFinalReversalCompensatesExactly() {
        Rating original = rate(bands(), "1100.00", "100.00", profile(120, PaymentType.INSTALLMENTS, ReceivedBy.AGENCY),
                true);
        Rating first = CommissionCalculator.reverse(original, Money.of("550.00"), ReceivedBy.AGENCY);
        // metade: honorários −50,00, comissão −60,00, valor fixo mantido; repasse −(550,00 − 50,00 − 60,00) = −440,00
        assertThat(first.recovered()).isEqualTo(Money.of("-550.00"));
        assertThat(first.agencyHonorarium()).isEqualTo(Money.of("-50.00"));
        assertThat(first.commission()).isEqualTo(Money.of("-60.00"));
        assertThat(first.fixedFee()).isEqualTo(Money.ZERO);
        assertThat(first.transferEffect()).isEqualTo(Money.of("-440.00"));
        Rating remaining = original.plus(first);
        Rating last = CommissionCalculator.reverse(remaining, Money.of("550.00"), ReceivedBy.AGENCY);
        // último estorno leva o saldo inteiro: −50,00, −60,00, valor fixo −25,00; repasse −415,00
        assertThat(last.fixedFee()).isEqualTo(Money.of("-25.00"));
        assertThat(last.transferEffect()).isEqualTo(Money.of("-415.00"));
        Rating net = remaining.plus(last);
        assertThat(net.recovered()).isEqualTo(Money.ZERO);
        assertThat(net.commission()).isEqualTo(Money.ZERO);
        assertThat(net.fixedFee()).isEqualTo(Money.ZERO);
        assertThat(net.agencyHonorarium()).isEqualTo(Money.ZERO);
        assertThat(net.transferEffect()).isEqualTo(Money.ZERO);
    }

    @Test
    void reversalRoundingNeverLeavesResidualCents() {
        CommissionRules rules = new CommissionRules(new BigDecimal("15"), List.of(), null,
                HonorariumPolicy.agencyFull(), false).validated();
        Rating original = rate(rules, "100.00", "0", profile(1, PaymentType.LUMP_SUM, ReceivedBy.AGENCY), false);
        // 33,33 de 100,00: comissão 15,00 × 0,3333 = 4,9995 → −5,00; o restante (66,67) leva −10,00
        Rating first = CommissionCalculator.reverse(original, Money.of("33.33"), ReceivedBy.AGENCY);
        assertThat(first.commission()).isEqualTo(Money.of("-5.00"));
        Rating last = CommissionCalculator.reverse(original.plus(first), Money.of("66.67"), ReceivedBy.AGENCY);
        assertThat(last.commission()).isEqualTo(Money.of("-10.00"));
        assertThat(original.plus(first).plus(last).transferEffect()).isEqualTo(Money.ZERO);
    }

    @Test
    void reversalAboveRemainingIsRejected() {
        Rating original = rate(bands(), "100.00", "0", profile(10, PaymentType.LUMP_SUM, ReceivedBy.AGENCY), false);
        assertThatThrownBy(() -> CommissionCalculator.reverse(original, Money.of("100.01"), ReceivedBy.AGENCY))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void invalidRulesAreRejected() {
        assertThatThrownBy(() -> new CommissionRules(new BigDecimal("101"), List.of(), null, null, true).validated())
                .hasMessageContaining("Percentual");
        assertThatThrownBy(() -> new CommissionRules(BigDecimal.TEN, List.of(
                rule("A", 0, null, 10, null, null, null, null, "5"),
                rule("A", 0, null, 20, null, null, null, null, "5")), null, null, true).validated())
                .hasMessageContaining("repetido");
        assertThatThrownBy(() -> new CommissionRules(BigDecimal.TEN, List.of(
                rule("SEM_CRITERIO", 0, null, null, null, null, null, null, "5")), null, null, true).validated())
                .hasMessageContaining("sem critério");
        assertThatThrownBy(() -> new CommissionRules(BigDecimal.TEN, List.of(
                rule("INVERTIDA", 0, null, 100, 10, null, null, null, "5")), null, null, true).validated())
                .hasMessageContaining("Fim da faixa");
        assertThatThrownBy(() -> new CommissionRules(BigDecimal.TEN, List.of(), new BigDecimal("-1"), null, true)
                .validated()).hasMessageContaining("Valor fixo");
    }
}
