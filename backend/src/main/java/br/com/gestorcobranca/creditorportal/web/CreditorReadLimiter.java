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
 * Limite das leituras agregadas do portal do credor (Início e atividade, fatia 7): janelas fixas de 1 minuto por
 * pessoa do credor e operação, por instância (o mesmo padrão do {@link CreditorUploadLimiter}). Excesso → 429 com
 * {@code Retry-After}, log de acesso {@code RATE_LIMITED} ({@code scope=creditor_read}, só na primeira recusa da
 * janela) e a métrica {@code creditor_portal.reads.rejected{operation,reason}} (toda recusa).
 */
@Component
class CreditorReadLimiter {

    /** Indicadores do Início e "Minha atividade" (por pessoa e minuto). */
    static final int READ_LIMIT_PER_MINUTE = 30;
    /** "Aguardando você" (também o contador do menu, lido a cada navegação). */
    static final int AWAITING_LIMIT_PER_MINUTE = 60;

    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    CreditorReadLimiter(CurrentActor currentActor, AccessLogger accessLogger, MeterRegistry registry, Clock clock) {
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.registry = registry;
        this.clock = clock;
    }

    void acquire(String operation, int limitPerMinute, HttpServletResponse response) {
        UUID person = currentActor.actorId();
        long epochSecond = Instant.now(clock).getEpochSecond();
        long minute = epochSecond / 60;
        Window window = windows.compute(person + ":" + operation, (k, current) ->
                current == null || current.minute() < minute ? new Window(minute, new AtomicInteger()) : current);
        int count = window.count().incrementAndGet();
        if (count <= limitPerMinute) {
            return;
        }
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(60 - (epochSecond % 60)));
        registry.counter("creditor_portal.reads.rejected", "operation", operation, "reason", "rate_limited").increment();
        // a métrica conta toda recusa; o log de acesso só a primeira da janela (o cliente insistindo não inunda o log)
        if (count == limitPerMinute + 1) {
            accessLogger.log(AccessEvent.of(AccessEventType.RATE_LIMITED, currentActor.get(),
                    Map.of("operation", operation, "limit", String.valueOf(limitPerMinute), "scope", "creditor_read")));
        }
        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Muitas consultas em pouco tempo. Aguarde um minuto e tente de novo.");
    }

    @Scheduled(fixedDelay = 60_000)
    void evictOldWindows() {
        long minute = Instant.now(clock).getEpochSecond() / 60;
        windows.entrySet().removeIf(e -> e.getValue().minute() < minute);
    }

    private record Window(long minute, AtomicInteger count) {
    }
}
