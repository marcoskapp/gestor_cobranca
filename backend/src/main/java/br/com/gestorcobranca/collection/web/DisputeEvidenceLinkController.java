package br.com.gestorcobranca.collection.web;

import br.com.gestorcobranca.collection.application.CollectionProperties;
import br.com.gestorcobranca.collection.application.port.in.DisputeEvidenceLinkUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeEvidenceLinkUseCases.Package;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Download público (sem login) das evidências enviadas ao credor por link seguro. Limite de acessos por IP e minuto
 * por instância (recomenda-se também regra de taxa no AWS WAF à frente do ALB); resposta sem cache.
 */
@RestController
class DisputeEvidenceLinkController {

    static final String PATH = ApiPaths.V1 + "/dispute-files/{token}";

    private final DisputeEvidenceLinkUseCases links;
    private final int limitPerMinute;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    DisputeEvidenceLinkController(DisputeEvidenceLinkUseCases links, CollectionProperties properties, Clock clock) {
        this.links = links;
        this.limitPerMinute = properties.disputes().linkRequestsPerMinute();
        this.clock = clock;
    }

    @GetMapping(PATH)
    void download(@PathVariable String token, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String ip = request.getRemoteAddr();
        if (!tryAcquire(ip)) {
            links.rateLimited(ip);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas; aguarde um minuto");
        }
        Package pack = links.open(token, ip);
        response.setStatus(HttpStatus.OK.value());
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(pack.fileName(), StandardCharsets.UTF_8).build().toString());
        links.write(pack, response.getOutputStream());
    }

    private boolean tryAcquire(String ip) {
        long minute = Instant.now(clock).getEpochSecond() / 60;
        Window window = windows.compute(String.valueOf(ip), (k, current) ->
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
