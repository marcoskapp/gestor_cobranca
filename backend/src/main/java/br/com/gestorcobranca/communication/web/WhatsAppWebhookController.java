package br.com.gestorcobranca.communication.web;

import br.com.gestorcobranca.communication.application.CommunicationProperties;
import br.com.gestorcobranca.communication.application.port.in.WebhookUseCases;
import br.com.gestorcobranca.communication.application.port.in.WebhookUseCases.Outcome;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Webhook do WhatsApp Cloud API ({@code PublicEndpoint}, sem JWT). Antes de qualquer processamento:
 * limite de taxa por rota e IP (429) e tamanho do corpo (413); a assinatura
 * {@code X-Hub-Signature-256} é validada pelo caso de uso sobre o corpo bruto (401 se inválida).
 * O handshake {@code GET} devolve o {@code hub.challenge} somente com o {@code hub.verify_token} da conta.
 */
@RestController
@RequestMapping(ApiPaths.WEBHOOKS + "/whatsapp")
class WhatsAppWebhookController {

    private static final String PROVIDER = "whatsapp";

    private final WebhookUseCases webhooks;
    private final WebhookRateLimiter rateLimiter;
    private final CommunicationProperties properties;
    private final MeterRegistry meterRegistry;

    WhatsAppWebhookController(WebhookUseCases webhooks, WebhookRateLimiter rateLimiter,
                              CommunicationProperties properties, MeterRegistry meterRegistry) {
        this.webhooks = webhooks;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @GetMapping(path = "/{routingKey}", produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> verify(@PathVariable String routingKey,
                                  @RequestParam(name = "hub.mode", required = false) String mode,
                                  @RequestParam(name = "hub.verify_token", required = false) String token,
                                  @RequestParam(name = "hub.challenge", required = false) String challenge,
                                  HttpServletRequest request) {
        if (!rateLimiter.tryAcquire(routingKey + "|" + request.getRemoteAddr())) {
            count("RATE_LIMITED");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        return webhooks.verifySubscription(PROVIDER, routingKey, mode, token, challenge, request.getRemoteAddr())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.FORBIDDEN).build());
    }

    @PostMapping("/{routingKey}")
    ResponseEntity<Void> receive(@PathVariable String routingKey,
                                 @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
                                 HttpServletRequest request) {
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
        Outcome outcome = webhooks.receive(PROVIDER, routingKey, signature, body, request.getRemoteAddr());
        HttpStatus status = switch (outcome) {
            case ACCEPTED, DUPLICATE -> HttpStatus.OK;
            case UNKNOWN_ROUTE -> HttpStatus.NOT_FOUND;
            case INVALID_SIGNATURE -> HttpStatus.UNAUTHORIZED;
            case MALFORMED -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).build();
    }

    private void count(String outcome) {
        meterRegistry.counter("communication.webhook.requests", "provider", PROVIDER, "outcome", outcome).increment();
    }
}
