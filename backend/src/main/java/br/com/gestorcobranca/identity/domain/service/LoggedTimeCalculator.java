package br.com.gestorcobranca.identity.domain.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Tempo logado a partir de intervalos de sessão: une intervalos sobrepostos do mesmo usuário
 * (sessões simultâneas não contam em dobro), recorta na janela pedida e distribui por dia no fuso
 * informado.
 */
public final class LoggedTimeCalculator {

    private LoggedTimeCalculator() {
    }

    public record Interval(UUID userId, Instant start, Instant end) {
    }

    /** @return segundos por usuário e dia (somente dias com tempo positivo), em ordem de data. */
    public static Map<UUID, Map<LocalDate, Long>> secondsByDay(List<Interval> intervals, Instant from, Instant to,
                                                               ZoneId zone) {
        Map<UUID, List<Interval>> byUser = new LinkedHashMap<>();
        for (Interval i : intervals) {
            Instant start = i.start().isBefore(from) ? from : i.start();
            Instant end = i.end().isAfter(to) ? to : i.end();
            if (end.isAfter(start)) {
                byUser.computeIfAbsent(i.userId(), k -> new ArrayList<>()).add(new Interval(i.userId(), start, end));
            }
        }
        Map<UUID, Map<LocalDate, Long>> result = new LinkedHashMap<>();
        byUser.forEach((user, list) -> {
            Map<LocalDate, Long> days = new TreeMap<>();
            for (Interval merged : merge(list)) {
                Instant cursor = merged.start();
                while (cursor.isBefore(merged.end())) {
                    LocalDate day = cursor.atZone(zone).toLocalDate();
                    Instant nextDay = day.plusDays(1).atStartOfDay(zone).toInstant();
                    Instant sliceEnd = nextDay.isBefore(merged.end()) ? nextDay : merged.end();
                    days.merge(day, Duration.between(cursor, sliceEnd).toSeconds(), Long::sum);
                    cursor = sliceEnd;
                }
            }
            days.values().removeIf(v -> v <= 0);
            if (!days.isEmpty()) {
                result.put(user, days);
            }
        });
        return result;
    }

    private static List<Interval> merge(List<Interval> intervals) {
        List<Interval> sorted = new ArrayList<>(intervals);
        sorted.sort(Comparator.comparing(Interval::start));
        List<Interval> merged = new ArrayList<>();
        Interval current = null;
        for (Interval i : sorted) {
            if (current == null) {
                current = i;
            } else if (!i.start().isAfter(current.end())) {
                current = new Interval(current.userId(), current.start(),
                        i.end().isAfter(current.end()) ? i.end() : current.end());
            } else {
                merged.add(current);
                current = i;
            }
        }
        if (current != null) {
            merged.add(current);
        }
        return merged;
    }
}
