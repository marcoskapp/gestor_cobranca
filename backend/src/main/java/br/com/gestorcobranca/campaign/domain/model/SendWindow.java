package br.com.gestorcobranca.campaign.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Janela de horário própria da etapa, no fuso de operação: dias da semana e intervalo
 * {@code [start, end)}. {@code start == end} = o dia todo; dias vazios = todos. Não substitui o
 * porteiro do compliance (janela legal, feriados, limites), que continua sendo verificado no envio;
 * serve para a estratégia da campanha (ex.: lembrete só de manhã, teste A/B de horário).
 */
@org.springframework.modulith.NamedInterface("usecases")
public record SendWindow(LocalTime start, LocalTime end, Set<DayOfWeek> days) {

    public SendWindow {
        start = start == null ? LocalTime.MIDNIGHT : start;
        end = end == null ? LocalTime.MIDNIGHT : end;
        days = days == null || days.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(days));
    }

    public static SendWindow always() {
        return new SendWindow(null, null, null);
    }

    public SendWindow validate() {
        Guard.require(start.equals(end) || start.isBefore(end), "cadence.window_invalid",
                "Janela da etapa: início deve ser anterior ao fim (sem atravessar a meia-noite)");
        return this;
    }

    public boolean allDay() {
        return start.equals(end);
    }

    public boolean contains(Instant at, ZoneId zone) {
        ZonedDateTime local = at.atZone(zone);
        if (!days.isEmpty() && !days.contains(local.getDayOfWeek())) {
            return false;
        }
        if (allDay()) {
            return true;
        }
        LocalTime time = local.toLocalTime();
        return !time.isBefore(start) && time.isBefore(end);
    }

    /** O próprio instante, se dentro da janela; senão a próxima abertura (até 8 dias adiante). */
    public Instant nextOpening(Instant at, ZoneId zone) {
        if (contains(at, zone)) {
            return at;
        }
        ZonedDateTime local = at.atZone(zone);
        for (int offset = 0; offset <= 7; offset++) {
            ZonedDateTime day = local.toLocalDate().plusDays(offset).atStartOfDay(zone);
            if (!days.isEmpty() && !days.contains(day.getDayOfWeek())) {
                continue;
            }
            ZonedDateTime opening = day.with(start);
            if (opening.isAfter(local)) {
                return opening.toInstant();
            }
        }
        throw new IllegalStateException("Janela sem abertura em 8 dias");
    }
}
