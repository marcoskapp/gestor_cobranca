package br.com.gestorcobranca.analytics.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

import java.time.LocalDate;
import java.util.Arrays;

/**
 * Períodos fechados dos indicadores do portal do credor (lista fixa: nada vem do cliente como data). Cada um tem o
 * período anterior de mesma duração em meses, para a variação: o mesmo trecho do mês (ou dos meses) anterior; quando o
 * período termina no último dia do mês, o anterior também termina no último dia do mês dele.
 */
public enum CreditorPeriod {
    /** Do dia 1 do mês até hoje. */
    THIS_MONTH(1),
    /** O mês anterior inteiro. */
    LAST_MONTH(1),
    /** O mês corrente e os dois anteriores, até hoje. */
    LAST_3_MONTHS(3),
    /** De 1º de janeiro até hoje. */
    THIS_YEAR(12);

    private final int months;

    CreditorPeriod(int months) {
        this.months = months;
    }

    /** Valor fora da lista → 422 {@code analytics.period_invalid} (a API já recusa com 400 antes de chegar aqui). */
    public static CreditorPeriod parse(String value) {
        CreditorPeriod period = value == null || value.isBlank() ? THIS_MONTH
                : Arrays.stream(values()).filter(p -> p.name().equals(value)).findFirst().orElse(null);
        Guard.require(period != null, "analytics.period_invalid", "Período não disponível");
        return period;
    }

    public Range range(LocalDate today) {
        return switch (this) {
            case THIS_MONTH -> new Range(today.withDayOfMonth(1), today);
            case LAST_MONTH -> {
                LocalDate first = today.withDayOfMonth(1).minusMonths(1);
                yield new Range(first, first.withDayOfMonth(first.lengthOfMonth()));
            }
            case LAST_3_MONTHS -> new Range(today.withDayOfMonth(1).minusMonths(2), today);
            case THIS_YEAR -> new Range(today.withDayOfYear(1), today);
        };
    }

    /** O período anterior de mesma duração, para a variação. */
    public Range previous(LocalDate today) {
        Range current = range(today);
        LocalDate to = current.to().minusMonths(months);
        if (current.to().getDayOfMonth() == current.to().lengthOfMonth()) {
            to = to.withDayOfMonth(to.lengthOfMonth());
        }
        return new Range(current.from().minusMonths(months), to);
    }

    public record Range(LocalDate from, LocalDate to) {
    }
}
