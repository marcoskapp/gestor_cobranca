package br.com.gestorcobranca.helpdesk.application.service;

import br.com.gestorcobranca.helpdesk.application.HelpdeskProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Perguntas por usuário por minuto, por instância (janela deslizante em memória, limitada a 10 mil usuários). Os
 * limites diários e por conversa ficam no banco ({@link ChatStore}) e valem para o conjunto das instâncias.
 */
@Component
class HelpdeskRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int MAX_USERS = 10_000;

    private final Map<UUID, Deque<Instant>> windows = new ConcurrentHashMap<>();
    private final HelpdeskProperties properties;
    private final Clock clock;

    HelpdeskRateLimiter(HelpdeskProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** @return {@code true} se a pergunta cabe no limite (e a registra) */
    boolean tryAcquire(UUID userId) {
        Instant now = clock.instant();
        if (windows.size() > MAX_USERS) {
            windows.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || e.getValue().peekLast().isBefore(now.minus(WINDOW));
                }
            });
        }
        Deque<Instant> window = windows.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && !window.peekFirst().isAfter(now.minus(WINDOW))) {
                window.pollFirst();
            }
            if (window.size() >= properties.limits().maxQuestionsPerMinute()) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }
}
