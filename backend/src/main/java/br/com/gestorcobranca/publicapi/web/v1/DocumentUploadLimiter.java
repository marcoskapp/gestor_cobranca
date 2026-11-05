package br.com.gestorcobranca.publicapi.web.v1;

import br.com.gestorcobranca.publicapi.application.PublicApiProperties;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.CurrentActor;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limite próprio dos envios com documentos (além do limite geral do token): janelas fixas de 1 minuto por token, por
 * instância (mesmo padrão do {@code TokenRateLimiter}). Excesso → 429 {@code rate_limited} com {@code Retry-After},
 * log de acesso {@code RATE_LIMITED} e métrica.
 */
@Component
class DocumentUploadLimiter {

    private final int limitPerMinute;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();

    DocumentUploadLimiter(PublicApiProperties properties, CurrentActor currentActor, AccessLogger accessLogger,
                          MeterRegistry registry, Clock clock) {
        this.limitPerMinute = properties.documentUploadsPerMinute();
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.registry = registry;
        this.clock = clock;
    }

    void acquire(UUID tokenId, String operation, HttpServletResponse response) {
        long epochSecond = Instant.now(clock).getEpochSecond();
        long minute = epochSecond / 60;
        Window window = windows.compute(tokenId, (k, current) ->
                current == null || current.minute() != minute ? new Window(minute, new AtomicInteger()) : current);
        if (window.count().incrementAndGet() <= limitPerMinute) {
            return;
        }
        long reset = 60 - (epochSecond % 60);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(reset));
        registry.counter("publicapi.document_uploads.rejected", "operation", operation).increment();
        accessLogger.log(AccessEvent.of(AccessEventType.RATE_LIMITED, currentActor.get(),
                Map.of("operation", operation, "limit", String.valueOf(limitPerMinute), "scope", "document_upload")));
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Limite de envios com documentos excedido (%d por minuto); tente novamente após o intervalo indicado"
                        .formatted(limitPerMinute));
    }

    @Scheduled(fixedDelay = 60_000)
    void evictOldWindows() {
        long minute = Instant.now(clock).getEpochSecond() / 60;
        windows.entrySet().removeIf(e -> e.getValue().minute() < minute);
    }

    private record Window(long minute, AtomicInteger count) {
    }
}
