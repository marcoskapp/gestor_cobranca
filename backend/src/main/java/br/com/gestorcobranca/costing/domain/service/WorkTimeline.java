package br.com.gestorcobranca.costing.domain.service;

import br.com.gestorcobranca.costing.domain.model.Usage.WorkSpan;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Linha do tempo de trabalho de cada pessoa (desenho margem-por-carteira §4.1): transforma os trechos de trabalho
 * (interações, pós-atendimento, respostas no chat) em segundos por dia × credor × carteira <b>sem contar duas vezes o
 * mesmo instante</b> da mesma pessoa.
 * <p>
 * Quem atende vários devedores ao mesmo tempo (chat em paralelo com ligação, conversas de credores diferentes) tem
 * cada instante dividido em partes iguais entre os atendimentos abertos naquele instante. O total por pessoa e dia é
 * a união dos trechos — nunca mais que o relógio —, e cada credor recebe a sua fração. Trechos que atravessam a
 * meia-noite (no fuso de operação) são cortados no dia; só os dias de {@code from} a {@code to} entram.
 */
public final class WorkTimeline {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal MILLIS_PER_SECOND = BigDecimal.valueOf(1000);

    private WorkTimeline() {
    }

    /** Pessoa num dia de negócio. */
    public record PersonDay(LocalDate day, UUID userId) {
    }

    /** Destino do tempo: credor e carteira (carteira pode ser nula). */
    public record Target(UUID creditorId, UUID portfolioId) {
    }

    private record Event(Instant at, boolean opens, Target target) {
    }

    /**
     * Segundos de trabalho por pessoa e dia, divididos por credor e carteira (ordem de entrada preservada).
     *
     * @param spans trechos de trabalho (início antes do fim; os demais são ignorados)
     * @param zone  fuso do dia de negócio
     */
    public static Map<PersonDay, Map<Target, BigDecimal>> split(Collection<WorkSpan> spans, ZoneId zone,
                                                                LocalDate from, LocalDate to) {
        Map<UUID, List<Event>> byUser = new LinkedHashMap<>();
        for (WorkSpan s : spans) {
            if (s.userId() == null || s.start() == null || s.end() == null || !s.start().isBefore(s.end())) {
                continue;
            }
            Target target = new Target(s.creditorId(), s.portfolioId());
            List<Event> events = byUser.computeIfAbsent(s.userId(), k -> new ArrayList<>());
            events.add(new Event(s.start(), true, target));
            events.add(new Event(s.end(), false, target));
        }
        Map<PersonDay, Map<Target, BigDecimal>> result = new LinkedHashMap<>();
        byUser.forEach((user, events) -> sweep(user, events, zone, from, to, result));
        return result;
    }

    /** Percorre os eventos em ordem: entre dois instantes, o trecho vai em partes iguais para os atendimentos abertos. */
    private static void sweep(UUID user, List<Event> events, ZoneId zone, LocalDate from, LocalDate to,
                              Map<PersonDay, Map<Target, BigDecimal>> result) {
        events.sort(Comparator.comparing(Event::at));
        Map<Target, Integer> open = new LinkedHashMap<>();
        int openCount = 0;
        Instant previous = null;
        for (Event e : events) {
            if (previous != null && openCount > 0 && previous.isBefore(e.at())) {
                distribute(user, previous, e.at(), open, openCount, zone, from, to, result);
            }
            if (e.opens()) {
                open.merge(e.target(), 1, Integer::sum);
                openCount++;
            } else {
                open.computeIfPresent(e.target(), (k, n) -> n > 1 ? n - 1 : null);
                openCount--;
            }
            previous = e.at();
        }
    }

    private static void distribute(UUID user, Instant start, Instant end, Map<Target, Integer> open, int openCount,
                                   ZoneId zone, LocalDate from, LocalDate to,
                                   Map<PersonDay, Map<Target, BigDecimal>> result) {
        Instant cursor = start;
        while (cursor.isBefore(end)) {
            LocalDate day = cursor.atZone(zone).toLocalDate();
            Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
            Instant segmentEnd = end.isBefore(dayEnd) ? end : dayEnd;
            if (!day.isBefore(from) && !day.isAfter(to)) {
                BigDecimal seconds = BigDecimal.valueOf(segmentEnd.toEpochMilli() - cursor.toEpochMilli())
                        .divide(MILLIS_PER_SECOND, MC);
                Map<Target, BigDecimal> targets = result.computeIfAbsent(new PersonDay(day, user),
                        k -> new LinkedHashMap<>());
                BigDecimal total = BigDecimal.valueOf(openCount);
                open.forEach((target, n) -> targets.merge(target,
                        seconds.multiply(BigDecimal.valueOf(n)).divide(total, MC), BigDecimal::add));
            }
            cursor = segmentEnd;
        }
    }

    /** Soma dos segundos de uma pessoa no dia (a união dos trechos). */
    public static BigDecimal total(Map<Target, BigDecimal> targets) {
        return targets.values().stream().filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
