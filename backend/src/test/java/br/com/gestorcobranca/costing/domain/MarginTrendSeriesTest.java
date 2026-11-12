package br.com.gestorcobranca.costing.domain;

import br.com.gestorcobranca.costing.domain.model.CostCategory;
import br.com.gestorcobranca.costing.domain.model.MarginLine;
import br.com.gestorcobranca.costing.domain.model.MarginTotals;
import br.com.gestorcobranca.costing.domain.service.MarginTrendSeries;
import br.com.gestorcobranca.costing.domain.service.MarginTrendSeries.MonthStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Tendência de 6 meses (desenho margem-por-carteira §14.1): janela, situação do mês e ponto vazio. */
class MarginTrendSeriesTest {

    @Test
    void janelaDeSeisMesesTerminaNoMesDoFimDoPeriodoAtravessandoOAno() {
        assertThat(MarginTrendSeries.window(YearMonth.of(2027, 2))).containsExactly(YearMonth.of(2026, 9),
                YearMonth.of(2026, 10), YearMonth.of(2026, 11), YearMonth.of(2026, 12), YearMonth.of(2027, 1),
                YearMonth.of(2027, 2));
    }

    @Test
    void situacaoDoMesPeloUltimoDiaApurado() {
        YearMonth nov = YearMonth.of(2026, 11);
        assertThat(MarginTrendSeries.status(nov, null)).isEqualTo(MonthStatus.NOT_REFRESHED);
        assertThat(MarginTrendSeries.status(nov, LocalDate.of(2026, 10, 31))).isEqualTo(MonthStatus.NOT_REFRESHED);
        assertThat(MarginTrendSeries.status(nov, LocalDate.of(2026, 11, 1))).isEqualTo(MonthStatus.PARTIAL);
        assertThat(MarginTrendSeries.status(nov, LocalDate.of(2026, 11, 29))).isEqualTo(MonthStatus.PARTIAL);
        assertThat(MarginTrendSeries.status(nov, LocalDate.of(2026, 11, 30))).isEqualTo(MonthStatus.COMPLETE);
        assertThat(MarginTrendSeries.status(YearMonth.of(2026, 6), LocalDate.of(2026, 11, 11)))
                .isEqualTo(MonthStatus.COMPLETE);
    }

    @Test
    void mesSemFatoOuSemApuracaoFicaVazioNuncaZero() {
        MarginTotals withData = new MarginTotals(new BigDecimal("1000"), new BigDecimal("200"),
                Map.of(CostCategory.HUMAN_DIRECT, new BigDecimal("50")), 1, BigDecimal.ONE, BigDecimal.ZERO);
        assertThat(MarginTrendSeries.point(null, MonthStatus.COMPLETE)).isNull();
        assertThat(MarginTrendSeries.point(withData, MonthStatus.NOT_REFRESHED)).isNull();
        MarginLine line = MarginTrendSeries.point(withData, MonthStatus.PARTIAL);
        assertThat(line.netMargin()).isEqualByComparingTo("150");
        assertThat(line.netMarginRate()).isEqualByComparingTo("0.75");
    }

    @Test
    void mesSoComCustoTemMargemNegativaESemTaxa() {
        MarginTotals onlyCost = new MarginTotals(BigDecimal.ZERO, BigDecimal.ZERO,
                Map.of(CostCategory.OVERHEAD, new BigDecimal("80")), 0, null, null);
        MarginLine line = MarginTrendSeries.point(onlyCost, MonthStatus.COMPLETE);
        assertThat(line.netMargin()).isEqualByComparingTo("-80");
        assertThat(line.netMarginRate()).isNull();
    }
}
