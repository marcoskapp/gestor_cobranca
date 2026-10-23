package br.com.gestorcobranca.incentive;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Relógio controlável dos testes de integração: permite provisionar a assessoria "no passado"
 * (plano vigente desde então) e depois avançar até depois da carência para fechar o período.
 * {@code null} = hora real.
 */
final class MutableClock extends Clock {

    private final AtomicReference<Instant> fixed = new AtomicReference<>();

    void set(Instant instant) {
        fixed.set(instant);
    }

    void reset() {
        fixed.set(null);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock self = this;
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId other) {
                return self.withZone(other);
            }

            @Override
            public Instant instant() {
                return self.instant();
            }
        };
    }

    @Override
    public Instant instant() {
        Instant value = fixed.get();
        return value == null ? Instant.now() : value;
    }
}
