package br.com.gestorcobranca.assistant.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Valores que a resposta do assistente pode citar: somente os devolvidos pelas ferramentas (motor de
 * negociação, collection) na conversa. Qualquer valor monetário, data, número de parcelas ou
 * percentual fora deste conjunto bloqueia a resposta ({@code ResponseGuard}).
 */
public record AllowedFacts(List<BigDecimal> amounts, List<LocalDate> dates, List<Integer> installments,
                           List<BigDecimal> percents) {

    public static final AllowedFacts EMPTY = new AllowedFacts(List.of(), List.of(), List.of(), List.of());

    public AllowedFacts {
        amounts = amounts == null ? List.of() : List.copyOf(amounts);
        dates = dates == null ? List.of() : List.copyOf(dates);
        installments = installments == null ? List.of() : List.copyOf(installments);
        percents = percents == null ? List.of() : List.copyOf(percents);
    }

    public AllowedFacts withAmounts(Collection<BigDecimal> values) {
        TreeSet<BigDecimal> set = new TreeSet<>(amounts);
        values.stream().filter(java.util.Objects::nonNull).map(AllowedFacts::money).forEach(set::add);
        return new AllowedFacts(List.copyOf(set), dates, installments, percents);
    }

    public AllowedFacts withDates(Collection<LocalDate> values) {
        TreeSet<LocalDate> set = new TreeSet<>(dates);
        values.stream().filter(java.util.Objects::nonNull).forEach(set::add);
        return new AllowedFacts(amounts, List.copyOf(set), installments, percents);
    }

    public AllowedFacts withInstallments(Collection<Integer> values) {
        TreeSet<Integer> set = new TreeSet<>(installments);
        values.stream().filter(java.util.Objects::nonNull).forEach(set::add);
        return new AllowedFacts(amounts, dates, List.copyOf(set), percents);
    }

    public AllowedFacts withPercents(Collection<BigDecimal> values) {
        TreeSet<BigDecimal> set = new TreeSet<>(percents);
        values.stream().filter(java.util.Objects::nonNull).map(AllowedFacts::percent).forEach(set::add);
        return new AllowedFacts(amounts, dates, installments, List.copyOf(set));
    }

    public boolean allowsAmount(BigDecimal value) {
        BigDecimal v = money(value);
        return amounts.stream().anyMatch(a -> a.compareTo(v) == 0);
    }

    /** Data citada com ano, ou só dia/mês (qualquer ano conhecido). */
    public boolean allowsDate(int day, int month, Integer year) {
        return dates.stream().anyMatch(d -> d.getDayOfMonth() == day && d.getMonthValue() == month
                && (year == null || d.getYear() == year));
    }

    public boolean allowsInstallments(int value) {
        return installments.contains(value);
    }

    public boolean allowsPercent(BigDecimal value) {
        BigDecimal v = percent(value);
        return percents.stream().anyMatch(p -> p.compareTo(v) == 0);
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal percent(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros();
    }
}
