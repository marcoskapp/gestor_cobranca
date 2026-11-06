package br.com.gestorcobranca.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Relógio controlável dos testes de integração: permite provisionar a assessoria "no passado"
 * (plano vigente desde então) e depois avançar até depois da carência para fechar o período, ou
 * avançar além de um prazo (janela do limite acumulado, validade de proposta). {@code null} = hora real.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> fixed = new AtomicReference<>();

    public void set(Instant instant) {
        fixed.set(instant);
    }

    public void reset() {
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
