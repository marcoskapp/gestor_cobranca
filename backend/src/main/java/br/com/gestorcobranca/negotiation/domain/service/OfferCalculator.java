package br.com.gestorcobranca.negotiation.domain.service;

import br.com.gestorcobranca.negotiation.domain.model.calculation.Breakdown;
import br.com.gestorcobranca.negotiation.domain.model.calculation.CalculationLine;
import br.com.gestorcobranca.negotiation.domain.model.calculation.CalculationLine.Rule;
import br.com.gestorcobranca.negotiation.domain.model.calculation.DebtComponent;
import br.com.gestorcobranca.negotiation.domain.model.calculation.DebtComposition;
import br.com.gestorcobranca.negotiation.domain.model.calculation.TitleComposition;
import br.com.gestorcobranca.negotiation.domain.model.offer.PaymentPlan;
import br.com.gestorcobranca.negotiation.domain.model.offer.PlanRequest;
import br.com.gestorcobranca.negotiation.domain.model.offer.PricedDebt;
import br.com.gestorcobranca.negotiation.domain.model.offer.ScheduledPayment;
import br.com.gestorcobranca.negotiation.domain.model.policy.ComponentRates;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.Charges;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.Installments;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyRules.ScheduleFrequency;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Aplica descontos a uma composição e monta o cronograma de pagamento. Puro.
 *
 * <h2>Descontos</h2>
 * Por título e por componente: desconto = arredondamento HALF_EVEN de (valor × %); líquido =
 * valor − desconto. Os honorários calculados são recalculados sobre a base já descontada (quem
 * paga menos juros paga menos honorários sobre eles) e só então recebem o próprio desconto.
 *
 * <h2>Cronograma</h2>
 * A contagem de parcelas inclui a entrada (à vista = 1 pagamento). Com entrada informada (ou
 * mínima exigida), o saldo é financiado em n−1 parcelas pela tabela Price à taxa do período;
 * sem entrada exigida, as n parcelas são iguais (série antecipada: a 1ª vence na data inicial).
 * Juros de cada parcela = saldo × taxa, arredondado; a última parcela absorve os centavos
 * residuais para zerar o saldo. Sem juros, o saldo é dividido em partes iguais com os centavos
 * restantes nas primeiras parcelas.
 */
public final class OfferCalculator {

    public PricedDebt price(DebtComposition composition, Charges charges, ComponentRates discounts) {
        Breakdown gross = Breakdown.ZERO;
        Breakdown net = Breakdown.ZERO;
        for (TitleComposition title : composition.titles()) {
            gross = gross.plus(title.gross());
            net = net.plus(priceTitle(title, charges, discounts));
        }
        Breakdown discount = gross.minus(net);
        List<CalculationLine> lines = new ArrayList<>();
        for (DebtComponent c : DebtComponent.values()) {
            if (discount.get(c).isPositive()) {
                lines.add(new CalculationLine(null, c, Rule.DISCOUNT, gross.get(c).amount(), discounts.get(c), null, null,
                        discount.get(c), c == DebtComponent.HONORARIUM ? "Inclui recálculo sobre a base descontada" : null));
            }
        }
        return new PricedDebt(discounts, gross, net, discount, lines);
    }

    private Breakdown priceTitle(TitleComposition title, Charges charges, ComponentRates discounts) {
        Breakdown gross = title.gross();
        Breakdown net = gross;
        for (DebtComponent c : List.of(DebtComponent.PRINCIPAL, DebtComponent.CORRECTION, DebtComponent.INTEREST,
                DebtComponent.FINE, DebtComponent.FEES)) {
            net = net.with(c, gross.get(c).minus(discountOf(gross.get(c), discounts.get(c))));
        }
        Money recognizedHonorarium = title.recognized().honorarium();
        Money honorarium = recognizedHonorarium;
        if (charges.honorariumPercent().signum() > 0) {
            Money base = DebtCalculator.honorariumBase(charges.honorariumBase(), net.with(DebtComponent.HONORARIUM, Money.ZERO));
            honorarium = honorarium.plus(Money.of(base.amount().multiply(DecimalMath.fraction(charges.honorariumPercent()),
                    DecimalMath.MC)));
        }
        // a base descontada nunca é maior que a original: sem desconto, o valor é idêntico ao bruto
        honorarium = honorarium.min(gross.honorarium());
        Money honorariumNet = honorarium.minus(discountOf(honorarium, discounts.honorarium()));
        return net.with(DebtComponent.HONORARIUM, honorariumNet);
    }

    private static Money discountOf(Money value, BigDecimal percent) {
        if (percent.signum() == 0 || !value.isPositive()) {
            return Money.ZERO;
        }
        return Money.of(value.amount().multiply(DecimalMath.fraction(percent), DecimalMath.MC));
    }

    /**
     * @param minimumDownPayment entrada mínima exigida (maior entre política e alçada); zero = sem exigência
     */
    public PaymentPlan plan(Money negotiated, PlanRequest request, Installments rules, Money minimumDownPayment) {
        if (!negotiated.isPositive()) {
            throw new BusinessRuleException("negotiation.nothing_to_pay", "Não há saldo a negociar");
        }
        int n = request.installments();
        if (n == 1) {
            ScheduledPayment single = new ScheduledPayment(1, request.firstDueDate(), negotiated, Money.ZERO, negotiated,
                    Money.ZERO);
            return new PaymentPlan(1, negotiated, negotiated, BigDecimal.ZERO.setScale(DecimalMath.FACTOR_DISPLAY_SCALE),
                    List.of(single), negotiated, Money.ZERO);
        }
        BigDecimal i = periodRate(rules);
        Money entry = request.downPayment();
        if (entry == null && minimumDownPayment.isPositive()) {
            entry = minimumDownPayment;
        }
        if (entry == null) {
            entry = equalPayment(negotiated, i, n);
        }
        if (!entry.isLessThan(negotiated)) {
            throw new BusinessRuleException("negotiation.down_payment_invalid",
                    "A entrada deve ser menor que o valor negociado em pagamento parcelado");
        }
        List<ScheduledPayment> schedule = new ArrayList<>();
        Money financed = negotiated.minus(entry);
        schedule.add(new ScheduledPayment(1, request.firstDueDate(), entry, Money.ZERO, entry, financed));
        int remaining = n - 1;
        if (i.signum() == 0) {
            Money[] parts = financed.split(remaining);
            Money balance = financed;
            for (int k = 0; k < remaining; k++) {
                balance = balance.minus(parts[k]);
                schedule.add(new ScheduledPayment(k + 2, dueDate(request.firstDueDate(), k + 1, rules), parts[k],
                        Money.ZERO, parts[k], balance));
            }
        } else {
            Money pmt = request.downPayment() == null && !minimumDownPayment.isPositive() ? entry
                    : Money.of(financed.amount().multiply(i, DecimalMath.MC).divide(
                    BigDecimal.ONE.subtract(DecimalMath.pow(BigDecimal.ONE.add(i), -remaining, 1)), DecimalMath.MC));
            Money balance = financed;
            for (int k = 0; k < remaining; k++) {
                Money interest = Money.of(balance.amount().multiply(i, DecimalMath.MC));
                Money amortization = k == remaining - 1 ? balance : pmt.minus(interest);
                Money amount = amortization.plus(interest);
                balance = balance.minus(amortization);
                schedule.add(new ScheduledPayment(k + 2, dueDate(request.firstDueDate(), k + 1, rules), amount, interest,
                        amortization, balance));
            }
        }
        Money total = schedule.stream().map(ScheduledPayment::amount).reduce(Money.ZERO, Money::plus);
        return new PaymentPlan(n, negotiated, entry, DecimalMath.display(i.multiply(BigDecimal.valueOf(100))), schedule,
                total, total.minus(negotiated));
    }

    /** Parcela da série antecipada: N × i / ((1 − (1+i)^−n) × (1+i)); sem juros, a 1ª parte da divisão. */
    private static Money equalPayment(Money negotiated, BigDecimal i, int n) {
        if (i.signum() == 0) {
            return negotiated.split(n)[0];
        }
        BigDecimal onePlus = BigDecimal.ONE.add(i);
        BigDecimal denominator = BigDecimal.ONE.subtract(DecimalMath.pow(onePlus, -n, 1)).multiply(onePlus, DecimalMath.MC);
        return Money.of(negotiated.amount().multiply(i, DecimalMath.MC).divide(denominator, DecimalMath.MC));
    }

    /** Taxa por período: mensal como está; a cada N dias, (1+i)^(N/30) − 1. */
    static BigDecimal periodRate(Installments rules) {
        BigDecimal monthly = DecimalMath.fraction(rules.monthlyInterestRate());
        if (monthly.signum() == 0 || rules.frequency() == ScheduleFrequency.MONTHLY) {
            return monthly;
        }
        return DecimalMath.pow(BigDecimal.ONE.add(monthly), rules.intervalDays(), 30).subtract(BigDecimal.ONE);
    }

    static LocalDate dueDate(LocalDate first, int periods, Installments rules) {
        return rules.frequency() == ScheduleFrequency.MONTHLY ? first.plusMonths(periods)
                : first.plusDays((long) periods * rules.intervalDays());
    }

    /** Entrada mínima da política: maior entre % do negociado (arredondado para cima ao centavo) e valor fixo. */
    public static Money policyMinimumDownPayment(Money negotiated, Installments rules) {
        Money byPercent = Money.of(negotiated.amount().multiply(rules.minDownPaymentPercent())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.CEILING));
        return byPercent.max(Money.of(rules.minDownPaymentAmount()));
    }

    /** Entrada mínima da alçada (% do negociado, arredondado para cima ao centavo). */
    public static Money shareOf(Money negotiated, BigDecimal percent) {
        return Money.of(negotiated.amount().multiply(percent).divide(BigDecimal.valueOf(100), 2, RoundingMode.CEILING));
    }
}
