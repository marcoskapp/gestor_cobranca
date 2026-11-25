package br.com.gestorcobranca.creditorportal.web;

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
 * Limite dos envios com documentos no portal do credor: janelas fixas de 1 minuto por pessoa do credor, por instância
 * (mesmo padrão do limite de documentos da API pública). Excesso → 429 com {@code Retry-After}, log de acesso
 * {@code RATE_LIMITED} e a métrica {@code creditor_portal.document_uploads.rejected}.
 */
@Component
class CreditorUploadLimiter {

    static final int LIMIT_PER_MINUTE = 10;
    /** Buscas de título por número no informe de pagamento (por pessoa e minuto). */
    static final int SEARCH_LIMIT_PER_MINUTE = 30;

    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    CreditorUploadLimiter(CurrentActor currentActor, AccessLogger accessLogger, MeterRegistry registry, Clock clock) {
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.registry = registry;
        this.clock = clock;
    }

    void acquire(String operation, HttpServletResponse response) {
        acquire(operation, LIMIT_PER_MINUTE, response);
    }

    /** Janela própria por pessoa e operação (ex.: envio com documentos, busca de títulos). */
    void acquire(String operation, int limitPerMinute, HttpServletResponse response) {
        UUID person = currentActor.actorId();
        long epochSecond = Instant.now(clock).getEpochSecond();
        long minute = epochSecond / 60;
        Window window = windows.compute(person + ":" + operation, (k, current) ->
                current == null || current.minute() != minute ? new Window(minute, new AtomicInteger()) : current);
        if (window.count().incrementAndGet() <= limitPerMinute) {
            return;
        }
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(60 - (epochSecond % 60)));
        registry.counter("creditor_portal.document_uploads.rejected", "operation", operation).increment();
        accessLogger.log(AccessEvent.of(AccessEventType.RATE_LIMITED, currentActor.get(),
                Map.of("operation", operation, "limit", String.valueOf(limitPerMinute), "scope", "creditor_upload")));
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Muitas tentativas em pouco tempo. Aguarde um minuto e tente de novo.");
    }

    @Scheduled(fixedDelay = 60_000)
    void evictOldWindows() {
        long minute = Instant.now(clock).getEpochSecond() / 60;
        windows.entrySet().removeIf(e -> e.getValue().minute() < minute);
    }

    private record Window(long minute, AtomicInteger count) {
    }
}
