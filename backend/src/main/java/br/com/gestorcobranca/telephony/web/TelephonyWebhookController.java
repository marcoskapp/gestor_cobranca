package br.com.gestorcobranca.telephony.web;

import br.com.gestorcobranca.shared.web.ApiPaths;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyWebhookUseCases;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyWebhookUseCases.Reply;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyWebhookUseCases.WebhookHttpRequest;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Webhook do provedor de telefonia ({@code PublicEndpoint}, sem JWT). Antes de qualquer
 * processamento: limite de taxa por rota e IP (429) e tamanho do corpo (413); a assinatura é
 * validada pelo caso de uso sobre a requisição bruta (401 se inválida ou fora da tolerância de tempo).
 * Somente cabeçalhos de assinatura/correlação ({@code x-*}) seguem para o adaptador. A URL pública
 * (base pública configurada + caminho + consulta crua) acompanha a requisição para as assinaturas
 * que cobrem a URL (Twilio, Plivo); a resposta pode trazer instruções da chamada (TwiML/Plivo XML).
 */
@RestController
@RequestMapping(ApiPaths.WEBHOOKS + "/telephony")
class TelephonyWebhookController {

    private static final int MAX_QUERY = 2000;

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
    ResponseEntity<String> receive(@PathVariable String routingKey, HttpServletRequest request) {
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
        String rawQuery = request.getQueryString();
        if (rawQuery != null && rawQuery.length() > MAX_QUERY) {
            count("TOO_LARGE");
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
        // URL pública exatamente como o provedor a chamou (Twilio e Plivo assinam a URL com a consulta)
        String url = properties.webhook().publicBaseUrl() + request.getRequestURI() + (rawQuery == null ? "" : "?" + rawQuery);
        Reply reply = webhooks.handle(new WebhookHttpRequest(routingKey, url, query(rawQuery), headers(request), body,
                request.getContentType(), request.getRemoteAddr()));
        HttpStatus status = switch (reply.outcome()) {
            case ACCEPTED, DUPLICATE -> HttpStatus.OK;
            case UNKNOWN_ROUTE -> HttpStatus.NOT_FOUND;
            case INVALID_SIGNATURE -> HttpStatus.UNAUTHORIZED;
            case MALFORMED -> HttpStatus.BAD_REQUEST;
        };
        if (reply.body() == null || status != HttpStatus.OK) {
            return ResponseEntity.status(status).build();
        }
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(reply.contentType())).body(reply.body());
    }

    /**
     * Consulta crua decodificada sem {@code getParameter} (que consumiria o corpo de formulário antes
     * da leitura do corpo bruto): primeiro valor de cada nome, até 20 nomes.
     */
    static Map<String, String> query(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty() || values.size() >= 20) {
                continue;
            }
            int eq = pair.indexOf('=');
            try {
                String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                values.putIfAbsent(name, value);
            } catch (IllegalArgumentException e) {
                // parâmetro malformado: ignorado (a assinatura cobre a URL crua)
            }
        }
        return values;
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
