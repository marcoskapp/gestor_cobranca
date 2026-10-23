package br.com.gestorcobranca.portal.web;

import br.com.gestorcobranca.portal.application.PortalProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limite por IP e minuto, por instância, nos endpoints públicos do acesso ao portal (antes de qualquer
 * consulta ao banco). Complementa o bloqueio progressivo persistido por documento e IP; em produção,
 * recomenda-se também regra de taxa no AWS WAF.
 */
@Component
class PortalRateLimiter {

    private final int limitPerMinute;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    PortalRateLimiter(PortalProperties properties, Clock clock) {
        this.limitPerMinute = properties.access().requestsPerMinutePerIp();
        this.clock = clock;
    }

    boolean tryAcquire(String ip) {
        long minute = Instant.now(clock).getEpochSecond() / 60;
        Window window = windows.compute(ip == null ? "unknown" : ip, (k, current) ->
                current == null || current.minute() != minute ? new Window(minute, new AtomicInteger()) : current);
        return window.count().incrementAndGet() <= limitPerMinute;
    }

    @Scheduled(fixedDelay = 60_000)
    void evictOldWindows() {
        long minute = Instant.now(clock).getEpochSecond() / 60;
        windows.entrySet().removeIf(e -> e.getValue().minute() < minute);
    }

    private record Window(long minute, AtomicInteger count) {
    }
}
