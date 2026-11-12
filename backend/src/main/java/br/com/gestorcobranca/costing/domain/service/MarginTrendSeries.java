package br.com.gestorcobranca.costing.domain.service;

import br.com.gestorcobranca.costing.domain.model.MarginLine;
import br.com.gestorcobranca.costing.domain.model.MarginTotals;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Tendência dos últimos meses por credor e carteira (desenho margem-por-carteira §6.1 e §14):
 * <ul>
 *   <li>a janela tem {@link #MONTHS} meses e termina no mês do fim do período filtrado, independente do início;</li>
 *   <li>a situação do mês vem do último dia apurado: nenhum dia apurado = {@link MonthStatus#NOT_REFRESHED}; apurado
 *       até antes do último dia do mês = {@link MonthStatus#PARTIAL}; senão {@link MonthStatus#COMPLETE};</li>
 *   <li>mês não apurado ou sem nenhum fato do corte (nem receita, nem custo) = ponto vazio ({@code null}), nunca zero
 *       inventado; com fatos, a linha do mês pelas fórmulas de {@link MarginCalculator} (taxa nula sem receita).</li>
 * </ul>
 */
public final class MarginTrendSeries {

    public static final int MONTHS = 6;

    public enum MonthStatus {
        COMPLETE, PARTIAL, NOT_REFRESHED
    }

    private MarginTrendSeries() {
    }

    /** Os {@link #MONTHS} meses que terminam em {@code end}, do mais antigo ao mais recente. */
    public static List<YearMonth> window(YearMonth end) {
        List<YearMonth> months = new ArrayList<>(MONTHS);
        for (int i = MONTHS - 1; i >= 0; i--) {
            months.add(end.minusMonths(i));
        }
        return months;
    }

    /** Situação do mês pela apuração ({@code computedUntil} nulo = nunca apurado). */
    public static MonthStatus status(YearMonth month, LocalDate computedUntil) {
        if (computedUntil == null || computedUntil.isBefore(month.atDay(1))) {
            return MonthStatus.NOT_REFRESHED;
        }
        return computedUntil.isBefore(month.atEndOfMonth()) ? MonthStatus.PARTIAL : MonthStatus.COMPLETE;
    }

    /**
     * Ponto do mês: {@code null} quando o mês não foi apurado ou o corte não tem fato nele ({@code totals} nulo).
     */
    public static MarginLine point(MarginTotals totals, MonthStatus status) {
        if (totals == null || status == MonthStatus.NOT_REFRESHED) {
            return null;
        }
        return MarginCalculator.line(totals);
    }
}
