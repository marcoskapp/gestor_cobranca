package br.com.gestorcobranca.sandbox.infrastructure.time;

import br.com.gestorcobranca.sandbox.application.port.out.DemoTimeMachine;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link Clock} deslocável do sandbox: o instante é o do relógio de base mais um deslocamento compartilhado por todas as
 * visões do relógio ({@link #withZone} devolve outra visão do <strong>mesmo</strong> deslocamento — serviços que guardam
 * {@code clock.withZone(...)} enxergam a viagem). Sem deslocamento é idêntico ao relógio de base.
 */
public final class ShiftableClock extends Clock implements DemoTimeMachine {

    private final Clock base;
    private final AtomicReference<Duration> offset;

    public ShiftableClock(Clock base) {
        this(base, new AtomicReference<>(Duration.ZERO));
    }

    private ShiftableClock(Clock base, AtomicReference<Duration> offset) {
        this.base = Objects.requireNonNull(base);
        this.offset = offset;
    }

    @Override
    public ZoneId getZone() {
        return base.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return zone.equals(base.getZone()) ? this : new ShiftableClock(base.withZone(zone), offset);
    }

    @Override
    public Instant instant() {
        Duration shift = offset.get();
        Instant now = base.instant();
        return shift.isZero() ? now : now.plus(shift);
    }

    @Override
    public long millis() {
        return instant().toEpochMilli();
    }

    @Override
    public void travelTo(Instant instant) {
        Instant now = base.instant();
        if (instant.isAfter(now)) {
            throw new IllegalArgumentException("o relógio de demonstração não vai para o futuro: " + instant);
        }
        offset.set(Duration.between(now, instant));
    }

    @Override
    public void backToPresent() {
        offset.set(Duration.ZERO);
    }

    @Override
    public boolean shifted() {
        return !offset.get().isZero();
    }

    @Override
    public Instant realNow() {
        return base.instant();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ShiftableClock c && c.base.equals(base) && c.offset == offset;
    }

    @Override
    public int hashCode() {
        return base.hashCode();
    }

    @Override
    public String toString() {
        return "ShiftableClock[" + base + ", offset=" + offset.get() + "]";
    }
}
