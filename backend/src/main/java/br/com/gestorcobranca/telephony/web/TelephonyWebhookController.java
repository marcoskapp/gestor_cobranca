package br.com.gestorcobranca.telephony.web;

import br.com.gestorcobranca.shared.web.ApiPaths;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyWebhookUseCases;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyWebhookUseCases.Outcome;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Webhook do provedor de telefonia ({@code PublicEndpoint}, sem JWT). Antes de qualquer
 * processamento: limite de taxa por rota e IP (429) e tamanho do corpo (413); a assinatura é
 * validada pelo caso de uso sobre o corpo bruto (401 se inválida ou fora da tolerância de tempo).
 * Somente cabeçalhos de assinatura/correlação ({@code x-*}) seguem para o adaptador.
 */
@RestController
@RequestMapping(ApiPaths.WEBHOOKS + "/telephony")
class TelephonyWebhookController {

    private final TelephonyWebhookUseCases webhooks;
    private final TelephonyWebhookRateLimiter rateLimiter;
    private final TelephonyProperties properties;
    private final MeterRegistry meterRegistry;

    TelephonyWebhookController(TelephonyWebhookUseCases webhooks, TelephonyWebhookRateLimiter rateLimiter,
                               TelephonyProperties properties, MeterRegistry meterRegistry) {
        this.webhooks = webhooks;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping("/{routingKey}")
    ResponseEntity<Void> receive(@PathVariable String routingKey, HttpServletRequest request) {
        if (!rateLimiter.tryAcquire(routingKey + "|" + request.getRemoteAddr())) {
            count("RATE_LIMITED");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        int max = properties.webhook().maxBodyBytes();
        if (request.getContentLengthLong() > max) {
            count("TOO_LARGE");
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(max + 1);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o corpo do webhook", e);
        }
        if (body.length > max) {
            count("TOO_LARGE");
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        Outcome outcome = webhooks.receive(routingKey, headers(request), body, request.getRemoteAddr());
        HttpStatus status = switch (outcome) {
            case ACCEPTED, DUPLICATE -> HttpStatus.OK;
            case UNKNOWN_ROUTE -> HttpStatus.NOT_FOUND;
            case INVALID_SIGNATURE -> HttpStatus.UNAUTHORIZED;
            case MALFORMED -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).build();
    }

    private static Map<String, String> headers(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith("x-") && headers.size() < 30) {
                String value = request.getHeader(name);
                if (value != null && value.length() <= 2000) {
                    headers.put(lower, value);
                }
            }
        }
        return headers;
    }

    private void count(String outcome) {
        meterRegistry.counter("telephony.webhook.requests", "provider", "unknown", "outcome", outcome).increment();
    }
}
