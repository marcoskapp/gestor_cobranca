package br.com.gestorcobranca.identity.domain.service;

import br.com.gestorcobranca.identity.domain.service.LoggedTimeCalculator.Interval;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LoggedTimeCalculatorTest {

    private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    private static final UUID USER = UUID.randomUUID();
    private static final Instant FROM = Instant.parse("2026-10-01T03:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-01T03:00:00Z");

    @Test
    void overlappingSessionsAreCountedOnce() {
        Map<UUID, Map<LocalDate, Long>> result = LoggedTimeCalculator.secondsByDay(List.of(
                new Interval(USER, Instant.parse("2026-10-05T12:00:00Z"), Instant.parse("2026-10-05T14:00:00Z")),
                new Interval(USER, Instant.parse("2026-10-05T13:30:00Z"), Instant.parse("2026-10-05T15:00:00Z")),
                new Interval(USER, Instant.parse("2026-10-05T16:00:00Z"), Instant.parse("2026-10-05T16:30:00Z"))),
                FROM, TO, ZONE);
        assertThat(result.get(USER)).containsExactly(Map.entry(LocalDate.of(2026, 10, 5), 3L * 3600 + 1800));
    }

    @Test
    void sessionCrossingMidnightIsSplitByBusinessDay() {
        // 23:00 do dia 05 até 01:00 do dia 06 no fuso de operação (UTC−3)
        Map<UUID, Map<LocalDate, Long>> result = LoggedTimeCalculator.secondsByDay(List.of(
                new Interval(USER, Instant.parse("2026-10-06T02:00:00Z"), Instant.parse("2026-10-06T04:00:00Z"))),
                FROM, TO, ZONE);
        assertThat(result.get(USER)).containsExactly(Map.entry(LocalDate.of(2026, 10, 5), 3600L),
                Map.entry(LocalDate.of(2026, 10, 6), 3600L));
    }

    @Test
    void intervalsAreClippedToTheRequestedWindow() {
        Map<UUID, Map<LocalDate, Long>> result = LoggedTimeCalculator.secondsByDay(List.of(
                new Interval(USER, Instant.parse("2026-09-30T23:00:00Z"), Instant.parse("2026-10-01T04:00:00Z")),
                new Interval(USER, Instant.parse("2026-10-10T10:00:00Z"), Instant.parse("2026-10-10T10:00:00Z"))),
                FROM, TO, ZONE);
        assertThat(result.get(USER)).containsExactly(Map.entry(LocalDate.of(2026, 10, 1), 3600L));
    }
}
