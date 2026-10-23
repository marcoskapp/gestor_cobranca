package br.com.gestorcobranca.communication.web;

import br.com.gestorcobranca.communication.application.CommunicationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limite de requisições de webhook por (rota, IP de origem) em janelas de 1 minuto, por instância.
 * Protege o processamento (validação de assinatura e banco) de inundações; em produção, recomenda-se
 * também regra de taxa no AWS WAF à frente do ALB.
 */
@Component
class WebhookRateLimiter {

    private final int limitPerMinute;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    WebhookRateLimiter(CommunicationProperties properties, Clock clock) {
        this.limitPerMinute = properties.webhook().requestsPerMinute();
        this.clock = clock;
    }

    boolean tryAcquire(String key) {
        long minute = Instant.now(clock).getEpochSecond() / 60;
        Window window = windows.compute(key, (k, current) ->
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
