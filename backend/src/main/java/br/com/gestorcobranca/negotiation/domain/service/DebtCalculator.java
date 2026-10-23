package br.com.gestorcobranca.negotiation.domain.service;

import br.com.gestorcobranca.negotiation.domain.model.calculation.Breakdown;
import br.com.gestorcobranca.negotiation.domain.model.calculation.CalculationLine;
import br.com.gestorcobranca.negotiation.domain.model.calculation.CalculationLine.Rule;
import br.com.gestorcobranca.negotiation.domain.model.calculation.DebtComponent;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Motor de cálculo da dívida (§2): títulos + encargos da política + data de referência →
 * {@link DebtComposition} com memória de cálculo. Puro e determinístico (sem relógio, banco ou
 * Spring).
 *
 * <h2>Convenções (contrato — mudar exige nova versão do motor)</h2>
 * <ol>
 *   <li><b>Dias de atraso</b>: dias corridos entre o vencimento (exclusive) e a data de referência
 *       (inclusive); zero se não vencido. Título não vencido não recebe encargos de mora.</li>
 *   <li><b>Correção monetária</b>: fator = Π (1 + rₘ)^(kₘ/nₘ) para cada mês m que intercepta o
 *       período, com rₘ a variação do mês, kₘ os dias do período no mês e nₘ os dias do mês (pro
 *       rata die composto). Meses posteriores à última competência publicada valem 0% (com aviso);
 *       lacuna antes dela é erro. Correção negativa (deflação acumulada) não reduz o principal.
 *       Valor = principal × (fator − 1).</li>
 *   <li><b>Base corrigida</b> B = principal + correção.</li>
 *   <li><b>Juros de mora</b>: taxa mensal i sobre mês comercial de 30 dias — simples:
 *       B × i × d/30; compostos: B × ((1+i)^(d/30) − 1).</li>
 *   <li><b>Multa</b>: B × m, uma vez, se houver atraso.</li>
 *   <li><b>Encargos autorizados</b>: fixos por título vencido ou percentuais sobre o principal.</li>
 *   <li><b>Encargos já reconhecidos</b> no razão (juros, multa, encargos, honorários em aberto)
 *       entram como estão, sem nova mora sobre eles.</li>
 *   <li><b>Honorários</b>: reconhecidos + base × h, com base conforme {@link HonorariumBase}
 *       (dívida atualizada inclui encargos reconhecidos).</li>
 *   <li><b>Arredondamento</b>: fatores com 34 dígitos significativos; cada componente de cada
 *       título é arredondado uma única vez para centavos com {@code HALF_EVEN} (bancário); totais
 *       são somas de valores já arredondados.</li>
 * </ol>
 */
public final class DebtCalculator {

    /** Versão das convenções acima, registrada em cada simulação para reprodução (RB03). */
    public static final int ENGINE_VERSION = 1;

    private static final BigDecimal THIRTY = BigDecimal.valueOf(30);

    /**
     * @param index série do índice da política (obrigatória se {@code charges} tiver correção)
     */
    public DebtComposition calculate(List<TitleSnapshot> titles, Charges charges, LocalDate referenceDate,
                                     IndexSeries index) {
        if (titles.isEmpty()) {
            throw new BusinessRuleException("negotiation.no_titles", "Informe ao menos um título");
        }
        if (charges.correctionIndexCode() != null
                && (index == null || !index.code().equals(charges.correctionIndexCode()))) {
            throw new IllegalArgumentException("Série do índice " + charges.correctionIndexCode() + " não informada");
        }
        Set<String> notes = new LinkedHashSet<>();
        List<TitleComposition> compositions = new ArrayList<>();
        Breakdown total = Breakdown.ZERO;
        Money paid = Money.ZERO;
        long maxDays = 0;
        for (TitleSnapshot title : titles) {
            TitleComposition c = calculateTitle(title, charges, referenceDate, index, notes);
            compositions.add(c);
            total = total.plus(c.gross());
            paid = paid.plus(c.paidTotal());
            maxDays = Math.max(maxDays, c.daysOverdue());
        }
        return new DebtComposition(referenceDate, compositions, total, paid, maxDays, List.copyOf(notes));
    }

    private TitleComposition calculateTitle(TitleSnapshot t, Charges charges, LocalDate ref, IndexSeries index,
                                            Set<String> notes) {
        List<CalculationLine> lines = new ArrayList<>();
        long days = Math.max(0, ChronoUnit.DAYS.between(t.dueDate(), ref));
        Money principal = t.principalBalance();
        lines.add(new CalculationLine(t.titleId(), DebtComponent.PRINCIPAL, Rule.PRINCIPAL_BALANCE, null, null, null,
                null, principal, null));

        // correção
        BigDecimal factor = BigDecimal.ONE;
        Money correction = Money.ZERO;
        if (days > 0 && charges.correctionIndexCode() != null) {
            factor = correctionFactor(t.dueDate(), ref, index, notes);
            BigDecimal raw = principal.amount().multiply(factor.subtract(BigDecimal.ONE), DecimalMath.MC);
            String note = null;
            if (raw.signum() < 0) {
                raw = BigDecimal.ZERO;
                note = "Deflação acumulada no período: correção não reduz o principal";
            }
            correction = Money.of(raw);
            lines.add(new CalculationLine(t.titleId(), DebtComponent.CORRECTION, Rule.INDEX_CORRECTION,
                    principal.amount(), null, days, DecimalMath.display(factor), correction,
                    note != null ? note : "Índice " + index.code()));
        }
        BigDecimal base = principal.amount().add(correction.amount());

        // juros de mora
        Money interest = Money.ZERO;
        if (days > 0 && charges.interestMonthlyRate().signum() > 0) {
            BigDecimal i = DecimalMath.fraction(charges.interestMonthlyRate());
            BigDecimal interestFactor;
            Rule rule;
            if (charges.interestMode() == InterestMode.COMPOUND) {
                interestFactor = DecimalMath.pow(BigDecimal.ONE.add(i), days, 30).subtract(BigDecimal.ONE);
                rule = Rule.INTEREST_COMPOUND;
            } else {
                interestFactor = i.multiply(BigDecimal.valueOf(days)).divide(THIRTY, DecimalMath.MC);
                rule = Rule.INTEREST_SIMPLE;
            }
            interest = Money.of(base.multiply(interestFactor, DecimalMath.MC));
            lines.add(new CalculationLine(t.titleId(), DebtComponent.INTEREST, rule, base, charges.interestMonthlyRate(),
                    days, DecimalMath.display(interestFactor), interest, null));
        }

        // multa
        Money fine = Money.ZERO;
        if (days > 0 && charges.finePercent().signum() > 0) {
            fine = Money.of(base.multiply(DecimalMath.fraction(charges.finePercent()), DecimalMath.MC));
            lines.add(new CalculationLine(t.titleId(), DebtComponent.FINE, Rule.FINE, base, charges.finePercent(), null,
                    null, fine, null));
        }

        // encargos autorizados
        Money fees = Money.ZERO;
        if (days > 0) {
            for (Fee fee : charges.fees()) {
                Money amount;
                if (fee.type() == FeeType.FIXED) {
                    amount = Money.of(fee.value());
                    lines.add(new CalculationLine(t.titleId(), DebtComponent.FEES, Rule.FEE_FIXED, null, null, null,
                            null, amount, fee.code()));
                } else {
                    amount = Money.of(principal.amount().multiply(DecimalMath.fraction(fee.value()), DecimalMath.MC));
                    lines.add(new CalculationLine(t.titleId(), DebtComponent.FEES, Rule.FEE_PERCENT, principal.amount(),
                            fee.value(), null, null, amount, fee.code()));
                }
                fees = fees.plus(amount);
            }
        }

        // encargos já reconhecidos no razão
        Breakdown recognized = t.recognized();
        for (DebtComponent c : List.of(DebtComponent.INTEREST, DebtComponent.FINE, DebtComponent.FEES,
                DebtComponent.HONORARIUM)) {
            if (recognized.get(c).isPositive()) {
                lines.add(new CalculationLine(t.titleId(), c, Rule.RECOGNIZED_CHARGE, null, null, null, null,
                        recognized.get(c), "Saldo reconhecido no razão do título"));
            }
        }
        interest = interest.plus(recognized.interest());
        fine = fine.plus(recognized.fine());
        fees = fees.plus(recognized.fees());

        // honorários
        Breakdown beforeHonorarium = new Breakdown(principal, correction, interest, fine, fees, Money.ZERO);
        Money honorariumBase = honorariumBase(charges.honorariumBase(), beforeHonorarium);
        Money honorarium = recognized.honorarium();
        if (charges.honorariumPercent().signum() > 0 && honorariumBase.isPositive()) {
            Money computed = Money.of(honorariumBase.amount().multiply(DecimalMath.fraction(charges.honorariumPercent()),
                    DecimalMath.MC));
            honorarium = honorarium.plus(computed);
            lines.add(new CalculationLine(t.titleId(), DebtComponent.HONORARIUM, Rule.HONORARIUM, honorariumBase.amount(),
                    charges.honorariumPercent(), null, null, computed, charges.honorariumBase().name()));
        }

        Breakdown gross = beforeHonorarium.with(DebtComponent.HONORARIUM, honorarium);
        return new TitleComposition(t.titleId(), t.portfolioId(), t.dueDate(), days, gross, recognized, honorariumBase,
                t.paidTotal(), factor, lines);
    }

    /** Base dos honorários calculados sobre os valores informados. */
    public static Money honorariumBase(HonorariumBase base, Breakdown values) {
        return switch (base) {
            case PRINCIPAL -> values.principal();
            case UPDATED_PRINCIPAL -> values.principal().plus(values.correction());
            case UPDATED_DEBT -> values.totalBeforeHonorarium();
        };
    }

    /** Fator de correção pro rata die composto no período (vencimento, referência]. */
    static BigDecimal correctionFactor(LocalDate dueDate, LocalDate ref, IndexSeries index, Set<String> notes) {
        LocalDate start = dueDate.plusDays(1);
        Optional<YearMonth> lastPublished = index.lastPublished();
        BigDecimal factor = BigDecimal.ONE;
        for (YearMonth m = YearMonth.from(start); !m.isAfter(YearMonth.from(ref)); m = m.plusMonths(1)) {
            LocalDate first = start.isAfter(m.atDay(1)) ? start : m.atDay(1);
            LocalDate last = ref.isBefore(m.atEndOfMonth()) ? ref : m.atEndOfMonth();
            long k = ChronoUnit.DAYS.between(first, last) + 1;
            Optional<BigDecimal> rate = index.rate(m);
            if (rate.isEmpty()) {
                if (lastPublished.isEmpty() || m.isAfter(lastPublished.get())) {
                    notes.add("Índice " + index.code() + " sem variação publicada para " + m + " (considerado 0%)");
                    continue;
                }
                throw new BusinessRuleException("negotiation.index_rate_missing",
                        "Índice " + index.code() + " sem variação cadastrada para " + m);
            }
            BigDecimal monthly = BigDecimal.ONE.add(DecimalMath.fraction(rate.get()));
            factor = factor.multiply(DecimalMath.pow(monthly, k, m.lengthOfMonth()), DecimalMath.MC);
        }
        return factor;
    }
}
