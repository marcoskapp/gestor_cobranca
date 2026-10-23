package br.com.gestorcobranca.negotiation.domain.model.offer;

import br.com.gestorcobranca.negotiation.domain.model.calculation.Breakdown;
import br.com.gestorcobranca.negotiation.domain.model.calculation.CalculationLine;
import br.com.gestorcobranca.negotiation.domain.model.policy.ComponentRates;

import java.util.List;

/**
 * Dívida com descontos aplicados.
 *
 * @param discountRates descontos pedidos por componente (%)
 * @param gross         composição sem desconto
 * @param net           composição após os descontos (honorários recalculados sobre a base descontada)
 * @param discount      {@code gross − net} por componente
 * @param lines         memória do desconto por componente
 */
public record PricedDebt(ComponentRates discountRates, Breakdown gross, Breakdown net, Breakdown discount,
                         List<CalculationLine> lines) {

    public PricedDebt {
        lines = List.copyOf(lines);
    }
}
