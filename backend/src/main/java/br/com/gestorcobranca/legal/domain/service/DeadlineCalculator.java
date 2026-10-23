package br.com.gestorcobranca.legal.domain.service;

import br.com.gestorcobranca.legal.domain.model.CountingMode;
import br.com.gestorcobranca.legal.domain.model.StartEvent;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Set;

/**
 * Contagem de prazos jurídicos (CPC arts. 219, 220 e 224). Puro, sem dependências.
 * <ul>
 *   <li><b>Dias úteis</b>: exclui o dia do começo e inclui o do vencimento; não contam sábados,
 *       domingos, feriados do calendário da assessoria (módulo compliance), suspensões forenses
 *       cadastradas (tribunal específico ou todos) e, se habilitado, o recesso de 20/12 a 20/01
 *       (art. 220).</li>
 *   <li><b>Dias corridos</b>: conta todos os dias; se o vencimento cair em dia não útil (fim de
 *       semana, feriado ou suspensão cadastrada), prorroga para o primeiro dia útil seguinte
 *       (art. 224 §1º). O recesso do art. 220 não suspende prazo em dias corridos.</li>
 *   <li><b>Data fixa</b>: a data designada, prorrogada da mesma forma se cair em dia não útil.</li>
 *   <li><b>Início</b>: na intimação, a contagem começa no dia útil seguinte; na disponibilização
 *       no DJe, a publicação é o dia útil seguinte e a contagem começa no dia útil seguinte a ela
 *       (art. 224 §§ 2º e 3º).</li>
 * </ul>
 */
public final class DeadlineCalculator {

    public static final int MAX_TERM = 365;

    private DeadlineCalculator() {
    }

    /** Período sem expediente forense (inclusive nas duas pontas). */
    public record Suspension(LocalDate from, LocalDate to) {

        public boolean contains(LocalDate date) {
            return !date.isBefore(from) && !date.isAfter(to);
        }
    }

    /**
     * Calendário forense aplicável ao prazo.
     *
     * @param holidays       feriados da assessoria
     * @param suspensions    suspensões cadastradas que alcançam o tribunal do prazo
     * @param yearEndRecess  aplica o recesso de 20/12 a 20/01 (CPC art. 220) aos prazos em dias úteis
     */
    public record ForensicCalendar(Set<LocalDate> holidays, List<Suspension> suspensions, boolean yearEndRecess) {

        public ForensicCalendar {
            holidays = holidays == null ? Set.of() : Set.copyOf(holidays);
            suspensions = suspensions == null ? List.of() : List.copyOf(suspensions);
        }

        /** Dia útil para contagem em dias úteis (inclui o recesso, se habilitado). */
        public boolean isBusinessDay(LocalDate date) {
            return isOpenDay(date) && !(yearEndRecess && inRecess(date));
        }

        /** Dia com expediente: não é fim de semana, feriado nem suspensão cadastrada. */
        public boolean isOpenDay(LocalDate date) {
            DayOfWeek day = date.getDayOfWeek();
            return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !holidays.contains(date)
                    && suspensions.stream().noneMatch(s -> s.contains(date));
        }

        static boolean inRecess(LocalDate date) {
            return (date.getMonth() == Month.DECEMBER && date.getDayOfMonth() >= 20)
                    || (date.getMonth() == Month.JANUARY && date.getDayOfMonth() <= 20);
        }

        LocalDate nextBusinessDay(LocalDate date) {
            LocalDate d = date.plusDays(1);
            while (!isBusinessDay(d)) {
                d = d.plusDays(1);
            }
            return d;
        }

        LocalDate firstOpenDayFrom(LocalDate date) {
            LocalDate d = date;
            while (!isOpenDay(d)) {
                d = d.plusDays(1);
            }
            return d;
        }
    }

    /**
     * Vencimento do prazo.
     *
     * @param start    data da intimação ou da disponibilização no DJe (ignorada em FIXED_DATE)
     * @param term     quantidade de dias (ignorada em FIXED_DATE)
     * @param fixedDue data designada (somente FIXED_DATE)
     */
    public static LocalDate dueDate(LocalDate start, StartEvent event, int term, CountingMode mode, LocalDate fixedDue,
                                    ForensicCalendar calendar) {
        if (mode == CountingMode.FIXED_DATE) {
            if (fixedDue == null) {
                throw new BusinessRuleException("legal_deadline.due_date_required", "Informe a data do prazo");
            }
            return calendar.firstOpenDayFrom(fixedDue);
        }
        if (start == null) {
            throw new BusinessRuleException("legal_deadline.start_required", "Informe a data de início do prazo");
        }
        if (term < 1 || term > MAX_TERM) {
            throw new BusinessRuleException("legal_deadline.term_invalid", "Prazo deve ter de 1 a %d dias".formatted(MAX_TERM));
        }
        LocalDate reference = event == StartEvent.DJE_AVAILABILITY ? calendar.nextBusinessDay(start) : start;
        if (mode == CountingMode.CALENDAR_DAYS) {
            return calendar.firstOpenDayFrom(reference.plusDays(term));
        }
        LocalDate date = reference;
        int counted = 0;
        while (counted < term) {
            date = date.plusDays(1);
            if (calendar.isBusinessDay(date)) {
                counted++;
            }
        }
        return date;
    }

    /**
     * Dias úteis restantes de {@code today} (exclusive) até {@code due} (inclusive): 0 quando vence
     * hoje, negativo quando já venceu.
     */
    public static int businessDaysRemaining(LocalDate today, LocalDate due, ForensicCalendar calendar) {
        if (due.isBefore(today)) {
            return -1;
        }
        int count = 0;
        LocalDate d = today;
        while (d.isBefore(due)) {
            d = d.plusDays(1);
            if (calendar.isOpenDay(d)) {
                count++;
            }
        }
        return count;
    }

    /** Fim da janela de calendário que contém o prazo (para buscar feriados e suspensões). */
    public static LocalDate horizon(LocalDate start, int term, CountingMode mode, LocalDate fixedDue) {
        if (mode == CountingMode.FIXED_DATE) {
            return fixedDue.plusDays(60);
        }
        return start.plusDays((mode == CountingMode.BUSINESS_DAYS ? term * 2L : term) + 90);
    }
}
