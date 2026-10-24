package br.com.gestorcobranca.settlement.domain.service;

import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules.RateRule;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules.ReceiptProfile;
import br.com.gestorcobranca.settlement.domain.model.rules.ReceivedBy;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.Percentage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cálculo puro da comissão da assessoria sobre um recebimento (RB08) e da compensação de um estorno.
 * Centavos arredondados HALF_EVEN em cada linha da memória; a memória reproduz o valor final.
 * <pre>
 * honorários da assessoria = honorários × fração da assessoria
 * base de comissão         = recuperado − honorários da assessoria
 * comissão                 = base × percentual da regra
 * valor fixo               = valor por acordo, no primeiro recebimento do acordo
 * efeito no repasse        = (recebido pela assessoria ? recuperado : 0) − honorários da assessoria − comissão − valor fixo
 * </pre>
 * Estorno: proporcional à parte do recebimento ainda não estornada; o último estorno (que zera o
 * recebimento) leva exatamente o saldo de cada componente, inclusive o valor fixo — sem resíduo de
 * centavos.
 */
public final class CommissionCalculator {

    private static final int RATIO_SCALE = 12;

    private CommissionCalculator() {
    }

    /**
     * @param amount       valor recuperado (positivo)
     * @param honorarium   parte do valor nos honorários cobrados do devedor (0 a {@code amount})
     * @param fixedFeeDue  primeiro recebimento do acordo (sem valor fixo líquido já devido)
     */
    public record Input(Money amount, Money honorarium, ReceiptProfile profile, boolean fixedFeeDue) {
    }

    public record MemoryLine(String step, String description, BigDecimal base, BigDecimal rate, BigDecimal amount) {
    }

    /** Resultado (ou posição líquida restante) de um recebimento; valores negativos num estorno. */
    public record Rating(String ruleCode, Percentage rate, Money recovered, Money agencyHonorarium,
                         Money creditorHonorarium, Money commissionBase, Money commission, Money fixedFee,
                         Money transferEffect, List<MemoryLine> memory) {

        public Rating {
            memory = memory == null ? List.of() : List.copyOf(memory);
        }

        public Money agencyRevenue() {
            return commission.plus(fixedFee).plus(agencyHonorarium);
        }

        /** Soma componente a componente (posição líquida após estornos). */
        public Rating plus(Rating other) {
            return new Rating(ruleCode, rate, recovered.plus(other.recovered),
                    agencyHonorarium.plus(other.agencyHonorarium), creditorHonorarium.plus(other.creditorHonorarium),
                    commissionBase.plus(other.commissionBase), commission.plus(other.commission),
                    fixedFee.plus(other.fixedFee), transferEffect.plus(other.transferEffect), List.of());
        }
    }

    public static Rating rate(CommissionRules rules, Input in) {
        Guard.require(in.amount() != null && in.amount().isPositive(), "commission.amount_invalid",
                "Recebimento deve ser positivo");
        Money honorarium = in.honorarium() == null ? Money.ZERO : in.honorarium();
        Guard.require(!honorarium.isNegative() && !honorarium.isGreaterThan(in.amount()),
                "commission.honorarium_invalid", "Honorários fora do valor recebido");
        List<MemoryLine> memory = new ArrayList<>();
        memory.add(new MemoryLine("RECOVERED", "Valor recuperado (pagamento confirmado e aplicado)", null, null,
                in.amount().amount()));

        Percentage share = Percentage.of(rules.honorarium().effectiveAgencyShare());
        Money agencyHonorarium = honorarium.percent(share);
        Money creditorHonorarium = honorarium.minus(agencyHonorarium);
        if (honorarium.isPositive()) {
            memory.add(new MemoryLine("AGENCY_HONORARIUM", "Honorários cobrados do devedor que pertencem à assessoria ("
                    + rules.honorarium().ownership() + ")", honorarium.amount(), share.value(),
                    agencyHonorarium.amount()));
            memory.add(new MemoryLine("CREDITOR_HONORARIUM", "Honorários repassados ao credor", honorarium.amount(),
                    null, creditorHonorarium.amount()));
        }
        Money base = in.amount().minus(agencyHonorarium);
        memory.add(new MemoryLine("COMMISSION_BASE", "Base de comissão = recuperado − honorários da assessoria",
                in.amount().amount(), null, base.amount()));

        Optional<RateRule> rule = rules.match(in.profile());
        Percentage rate = Percentage.of(rule.map(RateRule::rate).orElse(rules.defaultRate()));
        String code = rule.map(RateRule::code).orElse(CommissionRules.DEFAULT_RULE_CODE);
        Money commission = base.percent(rate);
        memory.add(new MemoryLine("COMMISSION", "Comissão (" + code + ")" + rule.map(r -> r.description() == null
                ? "" : " — " + r.description()).orElse(" — percentual padrão do contrato"), base.amount(),
                rate.value(), commission.amount()));

        Money fee = in.fixedFeeDue() && rules.fixedFeePerAgreement() != null
                ? Money.of(rules.fixedFeePerAgreement()) : Money.ZERO;
        if (fee.isPositive()) {
            memory.add(new MemoryLine("FIXED_FEE", "Valor fixo por acordo (primeiro recebimento do acordo)", null, null,
                    fee.amount()));
        }
        Money received = in.profile().receivedBy() == ReceivedBy.AGENCY ? in.amount() : Money.ZERO;
        Money transfer = received.minus(agencyHonorarium).minus(commission).minus(fee);
        memory.add(new MemoryLine("TRANSFER_EFFECT", in.profile().receivedBy() == ReceivedBy.AGENCY
                ? "Efeito no repasse = recebido na conta da assessoria − honorários da assessoria − comissão − valor fixo"
                : "Recebido pelo credor: o credor deve à assessoria honorários, comissão e valor fixo",
                received.amount(), null, transfer.amount()));
        return new Rating(code, rate, in.amount(), agencyHonorarium, creditorHonorarium, base, commission, fee, transfer,
                memory);
    }

    /**
     * Compensação de {@code reversed} (positivo) sobre a posição líquida restante do recebimento.
     *
     * @param remaining posição líquida (lançamento original + estornos anteriores)
     */
    public static Rating reverse(Rating remaining, Money reversed, ReceivedBy receivedBy) {
        Guard.require(reversed != null && reversed.isPositive() && !reversed.isGreaterThan(remaining.recovered()),
                "commission.reversal_invalid", "Estorno acima do saldo do recebimento");
        boolean last = reversed.equals(remaining.recovered());
        BigDecimal ratio = reversed.amount().divide(remaining.recovered().amount(), RATIO_SCALE, RoundingMode.HALF_EVEN);
        Money agencyHonorarium = last ? remaining.agencyHonorarium() : remaining.agencyHonorarium().times(ratio);
        Money creditorHonorarium = last ? remaining.creditorHonorarium() : remaining.creditorHonorarium().times(ratio);
        Money commission = last ? remaining.commission() : remaining.commission().times(ratio);
        Money fee = last ? remaining.fixedFee() : Money.ZERO;
        Money base = reversed.minus(agencyHonorarium);
        Money received = receivedBy == ReceivedBy.AGENCY ? reversed : Money.ZERO;
        Money transfer = received.minus(agencyHonorarium).minus(commission).minus(fee);
        List<MemoryLine> memory = List.of(
                new MemoryLine("REVERSED", last ? "Estorno do saldo restante do recebimento"
                        : "Estorno parcial (proporcional ao saldo restante)", remaining.recovered().amount(),
                        last ? null : ratio.multiply(new BigDecimal("100")).setScale(4, RoundingMode.HALF_EVEN),
                        reversed.negate().amount()),
                new MemoryLine("AGENCY_HONORARIUM", "Honorários da assessoria compensados", null, null,
                        agencyHonorarium.negate().amount()),
                new MemoryLine("COMMISSION", "Comissão compensada", null, remaining.rate().value(),
                        commission.negate().amount()),
                new MemoryLine("FIXED_FEE", last ? "Valor fixo compensado (recebimento zerado)"
                        : "Valor fixo mantido (estorno parcial)", null, null, fee.negate().amount()),
                new MemoryLine("TRANSFER_EFFECT", "Efeito no repasse", null, null, transfer.negate().amount()));
        return new Rating(remaining.ruleCode(), remaining.rate(), reversed.negate(), agencyHonorarium.negate(),
                creditorHonorarium.negate(), base.negate(), commission.negate(), fee.negate(), transfer.negate(), memory);
    }
}
