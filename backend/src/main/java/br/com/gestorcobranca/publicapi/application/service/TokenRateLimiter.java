package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.publicapi.application.port.in.gateway.IntegrationAuthentication.RateDecision;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limite de requisições por token em janelas fixas de 1 minuto, por instância (mesmo padrão dos demais limitadores da
 * plataforma, sem dependência de cache distribuído). Com N instâncias atrás do ALB o teto efetivo é até N vezes o
 * configurado; o limite global exato depende de serviço adicional (pendência registrada em
 * docs/tecnico/desenho/api-publica.md §5).
 */
@Component
class TokenRateLimiter {

    private final Clock clock;
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();

    TokenRateLimiter(Clock clock) {
        this.clock = clock;
    }

    RateDecision acquire(UUID tokenId, int limitPerMinute) {
        long epochSecond = Instant.now(clock).getEpochSecond();
        long minute = epochSecond / 60;
        Window window = windows.compute(tokenId, (k, current) ->
                current == null || current.minute() != minute ? new Window(minute, new AtomicInteger()) : current);
        int used = window.count().incrementAndGet();
        long reset = 60 - (epochSecond % 60);
        return new RateDecision(used <= limitPerMinute, limitPerMinute, Math.max(0, limitPerMinute - used), reset);
    }

    @Scheduled(fixedDelay = 60_000)
    void evictOldWindows() {
        long minute = Instant.now(clock).getEpochSecond() / 60;
        windows.entrySet().removeIf(e -> e.getValue().minute() < minute);
    }

    private record Window(long minute, AtomicInteger count) {
    }
}
