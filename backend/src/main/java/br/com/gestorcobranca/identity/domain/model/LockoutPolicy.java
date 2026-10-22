package br.com.gestorcobranca.identity.domain.model;

import java.time.Duration;

/**
 * Bloqueio progressivo por tentativas de login inválidas: após {@code maxFailedAttempts}
 * falhas consecutivas, a conta é bloqueada por {@code baseLockDuration × 2^(bloqueios anteriores)},
 * limitado a {@code maxLockDuration}. O desbloqueio é automático ao fim do prazo ou manual por
 * administrador.
 */
public record LockoutPolicy(int maxFailedAttempts, Duration baseLockDuration, Duration maxLockDuration) {

    public Duration lockDurationFor(int previousLockouts) {
        long factor = 1L << Math.min(previousLockouts, 10);
        Duration duration = baseLockDuration.multipliedBy(factor);
        return duration.compareTo(maxLockDuration) > 0 ? maxLockDuration : duration;
    }
}
