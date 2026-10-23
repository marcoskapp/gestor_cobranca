package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Forma canônica persistida de um conjunto de dias da semana: siglas em inglês, em ordem,
 * separadas por vírgula ({@code MON,TUE,WED}) — validada por check no banco.
 */
final class WeekDays {

    private WeekDays() {
    }

    static String format(Set<DayOfWeek> days) {
        Guard.require(days != null && !days.isEmpty(), "contact_window.days_required", "Informe ao menos um dia da semana");
        return EnumSet.copyOf(days).stream().map(WeekDays::code).collect(Collectors.joining(","));
    }

    static Set<DayOfWeek> parse(String value) {
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String code : value.split(",")) {
            days.add(Arrays.stream(DayOfWeek.values()).filter(d -> code(d).equals(code.trim()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Dia da semana inválido: " + code)));
        }
        return days;
    }

    private static String code(DayOfWeek day) {
        return day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT);
    }
}
