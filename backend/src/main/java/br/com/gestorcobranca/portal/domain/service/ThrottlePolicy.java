package br.com.gestorcobranca.portal.domain.service;

import java.time.Duration;
import java.time.Instant;

/**
 * Limite e bloqueio progressivo do acesso ao portal, por chave (documento ou IP), puro:
 * <ul>
 *   <li>contagens numa janela deslizante simples ({@code window}); fora dela recomeçam;</li>
 *   <li>{@code maxFailures} falhas de verificação (código, link, conhecimento) ou mais de
 *       {@code maxRequests} pedidos de código na janela bloqueiam a chave por
 *       {@code baseLock × 2^bloqueios anteriores}, até {@code maxLock};</li>
 *   <li>acesso concluído zera falhas e pedidos; no documento também zera o histórico de bloqueios.</li>
 * </ul>
 * O mesmo tratamento vale para documento inexistente: o bloqueio não revela existência.
 */
public final class ThrottlePolicy {

    public enum KeyType { DOCUMENT, IP }

    public record Limits(int maxFailures, int maxRequests, Duration window, Duration baseLock, Duration maxLock) {
    }

    /** @param lockedUntil fim do bloqueio vigente (nulo = sem bloqueio) */
    public record State(Instant windowStartedAt, int failures, int requests, int lockCount, Instant lockedUntil) {

        public static State fresh(Instant now) {
            return new State(now, 0, 0, 0, null);
        }

        public boolean lockedAt(Instant now) {
            return lockedUntil != null && lockedUntil.isAfter(now);
        }
    }

    private ThrottlePolicy() {
    }

    public static State onFailure(State s, Limits limits, Instant now) {
        State current = rolled(s, limits, now);
        int failures = current.failures() + 1;
        if (failures >= limits.maxFailures()) {
            return lock(current, limits, now);
        }
        return new State(current.windowStartedAt(), failures, current.requests(), current.lockCount(), current.lockedUntil());
    }

    public static State onRequest(State s, Limits limits, Instant now) {
        State current = rolled(s, limits, now);
        int requests = current.requests() + 1;
        if (requests > limits.maxRequests()) {
            return lock(current, limits, now);
        }
        return new State(current.windowStartedAt(), current.failures(), requests, current.lockCount(), current.lockedUntil());
    }

    public static State onSuccess(State s, Instant now, boolean forgetLocks) {
        return new State(now, 0, 0, forgetLocks ? 0 : s.lockCount(), null);
    }

    static Duration lockDuration(Limits limits, int previousLocks) {
        Duration d = limits.baseLock();
        for (int i = 0; i < previousLocks && d.compareTo(limits.maxLock()) < 0; i++) {
            d = d.multipliedBy(2);
        }
        return d.compareTo(limits.maxLock()) > 0 ? limits.maxLock() : d;
    }

    private static State lock(State current, Limits limits, Instant now) {
        Instant until = now.plus(lockDuration(limits, current.lockCount()));
        return new State(now, 0, 0, current.lockCount() + 1, until);
    }

    /** Janela vencida (e sem bloqueio vigente): contagens recomeçam, mantendo o histórico de bloqueios. */
    private static State rolled(State s, Limits limits, Instant now) {
        if (s.lockedAt(now)) {
            return s;
        }
        if (s.windowStartedAt() == null || !s.windowStartedAt().plus(limits.window()).isAfter(now)) {
            return new State(now, 0, 0, s.lockCount(), null);
        }
        return new State(s.windowStartedAt(), s.failures(), s.requests(), s.lockCount(), null);
    }
}
