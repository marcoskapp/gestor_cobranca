package br.com.gestorcobranca.sandbox.infrastructure.time;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShiftableClockTest {

    private static final Instant NOW = Instant.parse("2026-11-17T15:00:00Z");

    @Test
    void withoutTravelIsTheBaseClock() {
        ShiftableClock clock = new ShiftableClock(Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(clock.instant()).isEqualTo(NOW);
        assertThat(clock.shifted()).isFalse();
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void travelShiftsEveryZoneViewAndBackToPresentRestores() {
        ShiftableClock clock = new ShiftableClock(Clock.fixed(NOW, ZoneOffset.UTC));
        Clock saoPaulo = clock.withZone(ZoneId.of("America/Sao_Paulo"));
        Instant past = NOW.minus(Duration.ofDays(365));
        clock.travelTo(past);
        assertThat(clock.instant()).isEqualTo(past);
        // a visão em outro fuso, criada antes da viagem, enxerga o mesmo deslocamento
        assertThat(saoPaulo.instant()).isEqualTo(past);
        assertThat(clock.realNow()).isEqualTo(NOW);
        assertThat(clock.shifted()).isTrue();
        clock.backToPresent();
        assertThat(saoPaulo.instant()).isEqualTo(NOW);
        assertThat(clock.shifted()).isFalse();
    }

    @Test
    void timeKeepsFlowingFromTheChosenInstant() {
        MutableClock base = new MutableClock(NOW);
        ShiftableClock clock = new ShiftableClock(base);
        Instant past = NOW.minus(Duration.ofDays(30));
        clock.travelTo(past);
        base.now = NOW.plusSeconds(90);
        assertThat(clock.instant()).isEqualTo(past.plusSeconds(90));
    }

    @Test
    void neverTravelsToTheFuture() {
        ShiftableClock clock = new ShiftableClock(Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> clock.travelTo(NOW.plusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(clock.shifted()).isFalse();
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
