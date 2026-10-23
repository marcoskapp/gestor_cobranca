package br.com.gestorcobranca.negotiation.domain.model.offer;

import br.com.gestorcobranca.negotiation.domain.model.calculation.Breakdown;
import br.com.gestorcobranca.negotiation.domain.model.calculation.CalculationLine;
import br.com.gestorcobranca.negotiation.domain.model.policy.ComponentRates;

import java.util.List;
import java.util.UUID;

/**
 * Dívida com descontos aplicados.
 *
 * @param discountRates descontos pedidos por componente (%)
 * @param gross         composição sem desconto
 * @param net           composição após os descontos (honorários recalculados sobre a base descontada)
 * @param discount      {@code gross − net} por componente
 * @param lines         memória do desconto por componente
 * @param titles        composição bruta e líquida de cada título (base da distribuição dos pagamentos do
 *                      acordo entre títulos e componentes); a soma é {@code gross}/{@code net}
 */
public record PricedDebt(ComponentRates discountRates, Breakdown gross, Breakdown net, Breakdown discount,
                         List<CalculationLine> lines, List<TitlePricing> titles) {

    public PricedDebt {
        lines = List.copyOf(lines);
        titles = titles == null ? List.of() : List.copyOf(titles);
    }

    public PricedDebt(ComponentRates discountRates, Breakdown gross, Breakdown net, Breakdown discount,
                      List<CalculationLine> lines) {
        this(discountRates, gross, net, discount, lines, List.of());
    }

    /**
     * @param recognized encargos já reconhecidos no razão do título antes da negociação
     */
    public record TitlePricing(UUID titleId, Breakdown gross, Breakdown net, Breakdown recognized) {

        public Breakdown discount() {
            return gross.minus(net);
        }
    }
}
