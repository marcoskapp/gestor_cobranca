package br.com.gestorcobranca.identity.web;

import br.com.gestorcobranca.identity.application.IdentityProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limite de tentativas por IP em janelas de 1 minuto para os endpoints de autenticação
 * (login, MFA, redefinição de senha). Complementa o bloqueio por conta (persistido e global):
 * este limite é por instância e protege contra varredura de muitas contas a partir de um IP.
 * Em produção recomenda-se também regra de taxa no AWS WAF à frente do ALB.
 */
@Component
class LoginRateLimiter {

    private final int limitPerMinute;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    LoginRateLimiter(IdentityProperties properties, Clock clock) {
        this.limitPerMinute = properties.rateLimit().loginPerMinutePerIp();
        this.clock = clock;
    }

    /** @return true se a tentativa é permitida */
    public boolean tryAcquire(String key) {
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
