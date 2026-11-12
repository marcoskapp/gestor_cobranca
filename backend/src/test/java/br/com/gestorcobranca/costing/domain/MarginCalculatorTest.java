package br.com.gestorcobranca.costing.domain;

import br.com.gestorcobranca.costing.domain.model.CostCategory;
import br.com.gestorcobranca.costing.domain.model.MarginLine;
import br.com.gestorcobranca.costing.domain.model.MarginTotals;
import br.com.gestorcobranca.costing.domain.service.MarginCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Fórmulas da margem (desenho margem-por-carteira §5). */
class MarginCalculatorTest {

    @Test
    void margensRoiECustosUnitarios() {
        MarginTotals t = new MarginTotals(new BigDecimal("10000"), new BigDecimal("2000"), Map.of(
                CostCategory.HUMAN_DIRECT, new BigDecimal("600"),
                CostCategory.AI, new BigDecimal("50"),
                CostCategory.TELEPHONY, new BigDecimal("100"),
                CostCategory.MESSAGING, new BigDecimal("50"),
                CostCategory.VARIABLE_COMMISSION, new BigDecimal("150"),
                CostCategory.LEGAL, new BigDecimal("50"),
                CostCategory.HUMAN_IDLE, new BigDecimal("200"),
                CostCategory.OVERHEAD, new BigDecimal("300")), 4, new BigDecimal("20.5"), new BigDecimal("4"));
        MarginLine l = MarginCalculator.line(t);
        assertThat(l.directCost()).isEqualByComparingTo("1000");
        assertThat(l.totalCost()).isEqualByComparingTo("1500");
        assertThat(l.contributionMargin()).isEqualByComparingTo("1000");
        assertThat(l.netMargin()).isEqualByComparingTo("500");
        assertThat(l.contributionMarginRate()).isEqualByComparingTo("0.5");
        assertThat(l.netMarginRate()).isEqualByComparingTo("0.25");
        assertThat(l.roi()).isEqualByComparingTo("0.3333");
        assertThat(l.costPerRecovered()).isEqualByComparingTo("0.15");
        assertThat(l.costPerSettledAgreement()).isEqualByComparingTo("375");
        assertThat(l.settledAgreements()).isEqualTo(4);
        assertThat(l.costs()).containsOnlyKeys(CostCategory.values());
        assertThat(l.directHours()).isEqualByComparingTo("20.50");
    }

    @Test
    void taxasSemBaseFicamNulas() {
        MarginLine l = MarginCalculator.line(new MarginTotals(BigDecimal.ZERO, BigDecimal.ZERO,
                Map.of(CostCategory.OVERHEAD, new BigDecimal("10")), 0, null, null));
        assertThat(l.netMargin()).isEqualByComparingTo("-10");
        assertThat(l.contributionMarginRate()).isNull();
        assertThat(l.netMarginRate()).isNull();
        assertThat(l.costPerRecovered()).isNull();
        assertThat(l.costPerSettledAgreement()).isNull();
        assertThat(l.roi()).isEqualByComparingTo("-1");
        assertThat(MarginCalculator.empty().roi()).isNull();
        assertThat(MarginCalculator.empty().costs().get(CostCategory.AI)).isEqualByComparingTo("0");
    }

    @Test
    void somaDeCortes() {
        MarginTotals a = new MarginTotals(BigDecimal.ONE, BigDecimal.TEN, Map.of(CostCategory.AI, BigDecimal.ONE), 1,
                BigDecimal.ONE, BigDecimal.ZERO);
        MarginTotals sum = a.plus(a);
        assertThat(sum.revenue()).isEqualByComparingTo("20");
        assertThat(sum.cost(CostCategory.AI)).isEqualByComparingTo("2");
        assertThat(sum.settledAgreements()).isEqualTo(2);
    }
}
