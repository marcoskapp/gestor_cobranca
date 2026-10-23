package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Janela de contato em horário local do fuso de operação: dias da semana e faixa de horas
 * {@code [start, end)}.
 * <ul>
 *   <li>{@code start < end}: janela no mesmo dia (ex.: 08:00–20:00);</li>
 *   <li>{@code start == end}: dia inteiro;</li>
 *   <li>{@code start > end}: atravessa a meia-noite (ex.: 20:00–02:00). O trecho após a
 *       meia-noite pertence ao dia em que a janela abriu: com sexta permitida e sábado não,
 *       sábado 01:00 está dentro da janela (aberta na sexta).</li>
 * </ul>
 * O "dia de abertura" ({@link #openingDate}) é também o dia considerado para feriados.
 */
public record ContactWindow(LocalTime start, LocalTime end, Set<DayOfWeek> days) {

    public ContactWindow {
        Guard.require(start != null && end != null, "contact_window.hours_required", "Início e fim da janela obrigatórios");
        Guard.require(days != null && !days.isEmpty(), "contact_window.days_required",
                "Informe ao menos um dia da semana para a janela de contato");
        days = Set.copyOf(EnumSet.copyOf(days));
    }

    /** Dias úteis mais sábado/domingo conforme a configuração da assessoria. */
    public static ContactWindow ofTenant(LocalTime start, LocalTime end, boolean saturday, boolean sunday) {
        Set<DayOfWeek> days = EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
        if (saturday) {
            days.add(DayOfWeek.SATURDAY);
        }
        if (sunday) {
            days.add(DayOfWeek.SUNDAY);
        }
        return new ContactWindow(start, end, days);
    }

    public boolean isFullDay() {
        return start.equals(end);
    }

    public boolean crossesMidnight() {
        return start.isAfter(end);
    }

    public boolean contains(LocalDateTime local) {
        return openingDate(local).isPresent();
    }

    /** Dia em que abriu a janela que contém o instante local; vazio se estiver fora da janela. */
    public Optional<LocalDate> openingDate(LocalDateTime local) {
        LocalDate date = local.toLocalDate();
        LocalTime time = local.toLocalTime();
        if (isFullDay()) {
            return allowed(date);
        }
        if (!crossesMidnight()) {
            return !time.isBefore(start) && time.isBefore(end) ? allowed(date) : Optional.empty();
        }
        if (!time.isBefore(start)) {
            return allowed(date);
        }
        if (time.isBefore(end)) {
            return allowed(date.minusDays(1));
        }
        return Optional.empty();
    }

    /** O próprio instante, se dentro da janela; senão a próxima abertura a partir dele. */
    public LocalDateTime nextOpening(LocalDateTime from) {
        if (contains(from)) {
            return from;
        }
        for (int i = 0; i <= 7; i++) {
            LocalDate day = from.toLocalDate().plusDays(i);
            if (days.contains(day.getDayOfWeek())) {
                LocalDateTime opening = openingOf(day);
                if (!opening.isBefore(from)) {
                    return opening;
                }
            }
        }
        // inalcançável: há ao menos um dia permitido e a busca cobre uma semana completa
        throw new IllegalStateException("Janela de contato sem abertura");
    }

    /** Primeira abertura de janela num dia posterior a {@code day} (pular um feriado inteiro). */
    public LocalDateTime firstOpeningAfter(LocalDate day) {
        for (int i = 1; i <= 7; i++) {
            LocalDate candidate = day.plusDays(i);
            if (days.contains(candidate.getDayOfWeek())) {
                return openingOf(candidate);
            }
        }
        throw new IllegalStateException("Janela de contato sem abertura");
    }

    private LocalDateTime openingOf(LocalDate day) {
        return isFullDay() ? day.atStartOfDay() : day.atTime(start);
    }

    private Optional<LocalDate> allowed(LocalDate openingDay) {
        return days.contains(openingDay.getDayOfWeek()) ? Optional.of(openingDay) : Optional.empty();
    }
}
