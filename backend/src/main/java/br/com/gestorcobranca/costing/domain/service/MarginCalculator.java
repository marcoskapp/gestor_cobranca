package br.com.gestorcobranca.costing.domain.service;

import br.com.gestorcobranca.costing.domain.model.CostCategory;
import br.com.gestorcobranca.costing.domain.model.MarginLine;
import br.com.gestorcobranca.costing.domain.model.MarginTotals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Fórmulas da margem (desenho §5):
 * <ul>
 *   <li>custo direto = pessoas (direto) + IA + telefonia + mensagens + comissão variável + jurídico;</li>
 *   <li>custo total = custo direto + ociosidade + rateio de fixos;</li>
 *   <li>margem de contribuição = receita − custo direto; margem líquida = receita − custo total (= margem de
 *       contribuição − ociosidade − fixos);</li>
 *   <li>taxas de margem = margem ÷ receita (nulas com receita ≤ 0);</li>
 *   <li>ROI operacional = margem líquida ÷ custo total (nulo com custo total ≤ 0);</li>
 *   <li>custo por real recuperado = custo total ÷ recuperado (nulo com recuperado ≤ 0);</li>
 *   <li>custo por acordo cumprido = custo total ÷ acordos quitados (nulo sem acordo quitado).</li>
 * </ul>
 * As contas usam os valores com precisão total e arredondam só o resultado.
 */
public final class MarginCalculator {

    private static final int MONEY = 2;
    private static final int RATE = 4;

    private MarginCalculator() {
    }

    public static MarginLine line(MarginTotals t) {
        BigDecimal direct = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        EnumMap<CostCategory, BigDecimal> costs = new EnumMap<>(CostCategory.class);
        for (CostCategory c : CostCategory.values()) {
            BigDecimal value = t.cost(c);
            costs.put(c, money(value));
            total = total.add(value);
            if (c.direct()) {
                direct = direct.add(value);
            }
        }
        BigDecimal contribution = t.revenue().subtract(direct);
        BigDecimal net = t.revenue().subtract(total);
        return new MarginLine(money(t.recovered()), money(t.revenue()), Collections.unmodifiableMap(costs),
                money(direct), money(total), money(contribution), money(net),
                ratio(contribution, t.revenue(), RATE), ratio(net, t.revenue(), RATE), ratio(net, total, RATE),
                ratio(total, t.recovered(), RATE), t.settledAgreements(),
                ratio(total, BigDecimal.valueOf(t.settledAgreements()), MONEY), money(t.directHours()),
                money(t.idleHours()));
    }

    /** Linha de um corte sem nenhum dado (todas as somas zero). */
    public static MarginLine empty() {
        return line(new MarginTotals(BigDecimal.ZERO, BigDecimal.ZERO, Map.of(), 0, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY, RoundingMode.HALF_EVEN);
    }

    /** Divisão com o denominador positivo; sem base, nula. */
    static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator, int scale) {
        if (denominator == null || denominator.signum() <= 0) {
            return null;
        }
        return numerator.divide(denominator, scale, RoundingMode.HALF_EVEN);
    }
}
