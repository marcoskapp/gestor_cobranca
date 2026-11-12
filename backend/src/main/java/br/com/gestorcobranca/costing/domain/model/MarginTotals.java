package br.com.gestorcobranca.costing.domain.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Somas de um corte (assessoria, credor, carteira ou mês) antes das fórmulas: recuperado, receita da assessoria, custo
 * por categoria, acordos quitados e horas. Imutável; {@link #plus(MarginTotals)} soma dois cortes.
 */
public record MarginTotals(BigDecimal recovered, BigDecimal revenue, Map<CostCategory, BigDecimal> costs,
                           long settledAgreements, BigDecimal directHours, BigDecimal idleHours) {

    public static final MarginTotals ZERO = new MarginTotals(BigDecimal.ZERO, BigDecimal.ZERO, Map.of(), 0,
            BigDecimal.ZERO, BigDecimal.ZERO);

    public MarginTotals {
        EnumMap<CostCategory, BigDecimal> all = new EnumMap<>(CostCategory.class);
        for (CostCategory c : CostCategory.values()) {
            BigDecimal v = costs == null ? null : costs.get(c);
            all.put(c, v == null ? BigDecimal.ZERO : v);
        }
        costs = Collections.unmodifiableMap(all);
        recovered = recovered == null ? BigDecimal.ZERO : recovered;
        revenue = revenue == null ? BigDecimal.ZERO : revenue;
        directHours = directHours == null ? BigDecimal.ZERO : directHours;
        idleHours = idleHours == null ? BigDecimal.ZERO : idleHours;
    }

    public BigDecimal cost(CostCategory category) {
        return costs.get(category);
    }

    public MarginTotals plus(MarginTotals other) {
        EnumMap<CostCategory, BigDecimal> sum = new EnumMap<>(CostCategory.class);
        for (CostCategory c : CostCategory.values()) {
            sum.put(c, cost(c).add(other.cost(c)));
        }
        return new MarginTotals(recovered.add(other.recovered), revenue.add(other.revenue), sum,
                settledAgreements + other.settledAgreements, directHours.add(other.directHours),
                idleHours.add(other.idleHours));
    }
}
