package br.com.gestorcobranca.payment.web;

import br.com.gestorcobranca.payment.application.PaymentProperties;
import br.com.gestorcobranca.payment.application.port.in.PixWebhookUseCases;
import br.com.gestorcobranca.payment.application.port.in.PixWebhookUseCases.Outcome;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Webhook Pix ({@code PublicEndpoint}, sem JWT). O PSP acrescenta {@code /pix} à URL cadastrada, então os
 * dois caminhos são aceitos. Antes de qualquer processamento: limite de taxa por rota e IP (429) e
 * tamanho do corpo (413). A autenticação (mTLS — certificado repassado pelo ALB — ou HMAC
 * {@code X-Pix-Signature}) é feita pelo caso de uso sobre o corpo bruto (401 se inválida).
 */
@RestController
@RequestMapping(ApiPaths.WEBHOOKS + "/pix")
class PixWebhookController {

    static final String SIGNATURE_HEADER = "X-Pix-Signature";

    private final PixWebhookUseCases webhooks;
    private final PixWebhookRateLimiter rateLimiter;
    private final PaymentProperties properties;
    private final MeterRegistry meterRegistry;

    PixWebhookController(PixWebhookUseCases webhooks, PixWebhookRateLimiter rateLimiter, PaymentProperties properties,
                         MeterRegistry meterRegistry) {
        this.webhooks = webhooks;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @PostMapping({"/{routingKey}", "/{routingKey}/pix"})
    ResponseEntity<Void> receive(@PathVariable String routingKey,
                                 @RequestHeader(name = SIGNATURE_HEADER, required = false) String signature,
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
        String clientCertificate = request.getHeader(properties.webhook().clientCertificateHeader());
        Outcome outcome = webhooks.receive(routingKey, signature, clientCertificate, body, request.getRemoteAddr());
        HttpStatus status = switch (outcome) {
            case ACCEPTED, DUPLICATE -> HttpStatus.OK;
            case UNKNOWN_ROUTE -> HttpStatus.NOT_FOUND;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case MALFORMED -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).build();
    }

    private void count(String outcome) {
        meterRegistry.counter("payment.webhook.requests", "provider", "pix", "outcome", outcome).increment();
    }
}
