package br.com.gestorcobranca.incentive.domain.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * Contagem de dias úteis (segunda a sexta, exceto feriados do calendário da assessoria) para a
 * carência dos lançamentos (§5.4).
 */
public final class BusinessDays {

    private BusinessDays() {
    }

    public static boolean isBusinessDay(LocalDate date, Set<LocalDate> holidays) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !holidays.contains(date);
    }

    /**
     * Data D+N: o N-ésimo dia útil após {@code start} (o próprio {@code start} não conta). Com N = 0
     * devolve {@code start}. Ex.: sexta 23/10/2026 + 5 dias úteis = sexta 30/10/2026; com feriado
     * em 28/10, segunda 02/11 (se não for feriado).
     */
    public static LocalDate plus(LocalDate start, int businessDays, Set<LocalDate> holidays) {
        if (businessDays < 0) {
            throw new IllegalArgumentException("Dias úteis não pode ser negativo");
        }
        LocalDate date = start;
        int counted = 0;
        while (counted < businessDays) {
            date = date.plusDays(1);
            if (isBusinessDay(date, holidays)) {
                counted++;
            }
        }
        return date;
    }

    /** Janela de calendário suficiente para conter N dias úteis a partir da data (para buscar feriados). */
    public static LocalDate horizon(LocalDate start, int businessDays) {
        return start.plusDays(businessDays * 2L + 30);
    }
}
