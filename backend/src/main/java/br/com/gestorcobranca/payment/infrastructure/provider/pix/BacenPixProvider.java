package br.com.gestorcobranca.payment.infrastructure.provider.pix;

import br.com.gestorcobranca.payment.application.PaymentProperties;
import br.com.gestorcobranca.payment.application.port.out.PixProvider;
import br.com.gestorcobranca.payment.domain.model.PixProviderType;
import br.com.gestorcobranca.shared.json.JsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Adaptador genérico da API Pix do BACEN (padrão seguido por qualquer PSP): OAuth2
 * {@code client_credentials} sobre TLS mútuo, {@code PUT /cob/{txid}} e {@code PUT /cobv/{txid}}
 * (idempotentes pelo {@code txid}), {@code PATCH} de cancelamento ({@code REMOVIDA_PELO_USUARIO_RECEBEDOR}),
 * {@code GET /pix/{e2eid}} e {@code PUT /webhook/{chave}}.
 * <p>
 * 429, 5xx, falhas de rede e 401 (token renovado na próxima tentativa) são transitórios; demais 4xx são
 * recusa definitiva com o código do PSP ({@code PIX_<tipo do erro>}). Métricas
 * {@code integration.calls{provider=pix_bacen,operation,outcome}} e {@code integration.calls.duration}.
 * Credenciais, token, documento e nome do pagador nunca vão para log.
 */
@Component
class BacenPixProvider implements PixProvider {

    static final String PROVIDER = "pix_bacen";
    private static final Logger LOG = LoggerFactory.getLogger(BacenPixProvider.class);

    private final PixHttpClientFactory clients;
    private final PaymentProperties properties;
    private final JsonCodec json;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final PixEndpointGuard endpoints;
    private final Map<UUID, Token> tokens = new ConcurrentHashMap<>();

    BacenPixProvider(PixHttpClientFactory clients, PaymentProperties properties, JsonCodec json,
                     MeterRegistry meterRegistry, Clock clock, PixEndpointGuard endpoints) {
        this.clients = clients;
        this.endpoints = endpoints;
        this.properties = properties;
        this.json = json;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    private record Token(String value, Instant expiresAt, Instant credentialsUpdatedAt) {
    }

    @Override
    public PixProviderType type() {
        return PixProviderType.BACEN_API;
    }

    @Override
    public ChargeResult createCharge(PixConnection connection, ChargeRequest request) {
        String path = (request.due() ? "/cobv/" : "/cob/") + request.txid();
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> calendar = new LinkedHashMap<>();
        if (request.due()) {
            calendar.put("dataDeVencimento", request.dueDate().toString());
            calendar.put("validadeAposVencimento", request.validityAfterDueDays() == null ? 0 : request.validityAfterDueDays());
        } else {
            calendar.put("expiracao", request.expirationSeconds());
        }
        body.put("calendario", calendar);
        if (request.payer() != null) {
            String document = request.payer().document();
            body.put("devedor", Map.of(document.length() == 14 ? "cnpj" : "cpf", document, "nome", request.payer().name()));
        }
        body.put("valor", Map.of("original", request.amount().setScale(2).toPlainString()));
        body.put("chave", connection.pixKey());
        if (request.description() != null) {
            body.put("solicitacaoPagador", request.description());
        }
        String bearer = "Bearer " + token(connection);
        JsonNode response = call(connection, request.due() ? "create_cobv" : "create_cob", () -> clients.client(connection)
                .put().uri(connection.apiBaseUrl() + path)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).body(json.toJson(body)).retrieve().body(String.class));
        String copyPaste = response.path("pixCopiaECola").asString("");
        if (copyPaste.isBlank()) {
            throw new ProviderException("PIX_INVALID_RESPONSE", "Resposta do PSP sem pixCopiaECola", true, null);
        }
        String location = response.path("loc").path("location").asString(response.path("location").asString(""));
        return new ChargeResult(response.path("txid").asString(request.txid()),
                response.path("revisao").isNumber() ? response.path("revisao").asInt() : null,
                location.isBlank() ? null : location, copyPaste, response.path("status").asString(null));
    }

    @Override
    public void cancelCharge(PixConnection connection, String txid, boolean due) {
        String bearer = "Bearer " + token(connection);
        call(connection, "cancel_charge", () -> clients.client(connection).patch()
                .uri(connection.apiBaseUrl() + (due ? "/cobv/" : "/cob/") + txid)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.toJson(Map.of("status", "REMOVIDA_PELO_USUARIO_RECEBEDOR"))).retrieve().body(String.class));
    }

    @Override
    public Optional<PixTransaction> findPix(PixConnection connection, String endToEndId) {
        String bearer = "Bearer " + token(connection);
        JsonNode node;
        try {
            node = call(connection, "find_pix", () -> clients.client(connection).get()
                    .uri(connection.apiBaseUrl() + "/pix/" + endToEndId)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .accept(MediaType.APPLICATION_JSON).retrieve().body(String.class));
        } catch (NotFound e) {
            return Optional.empty();
        }
        List<Refund> refunds = new ArrayList<>();
        for (JsonNode r : node.path("devolucoes")) {
            JsonNode at = r.path("horario");
            refunds.add(new Refund(r.path("id").asString(null), r.path("rtrId").asString(null),
                    decimal(r.path("valor").asString(null)), r.path("status").asString(null),
                    instant(at.path("liquidacao").asString(at.path("solicitacao").asString(null)))));
        }
        return Optional.of(new PixTransaction(node.path("endToEndId").asString(endToEndId),
                node.path("txid").asString(null), decimal(node.path("valor").asString(null)),
                instant(node.path("horario").asString(null)), refunds));
    }

    @Override
    public void configureWebhook(PixConnection connection, String webhookUrl) {
        String bearer = "Bearer " + token(connection);
        call(connection, "configure_webhook", () -> clients.client(connection).put()
                .uri(connection.apiBaseUrl() + "/webhook/" + connection.pixKey())
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).body(json.toJson(Map.of("webhookUrl", webhookUrl)))
                .retrieve().body(String.class));
    }

    /** Token OAuth2 em cache por conta, renovado antes de expirar ou quando as credenciais mudam. */
    String token(PixConnection connection) {
        Token cached = tokens.get(connection.accountId());
        Instant now = Instant.now(clock);
        if (cached != null && Objects.equals(cached.credentialsUpdatedAt(), connection.credentialsUpdatedAt())
                && cached.expiresAt().minus(properties.pix().tokenRefreshMargin()).isAfter(now)) {
            return cached.value();
        }
        String clientId = connection.credentials().get("clientId");
        String clientSecret = connection.credentials().get("clientSecret");
        if (clientId == null || clientSecret == null) {
            throw new ProviderException("PIX_CREDENTIALS_MISSING", "Conta Pix sem credenciais OAuth2", false, null);
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", properties.pix().scope());
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        JsonNode response = call(connection, "oauth_token", () -> clients.client(connection).post()
                .uri(connection.tokenUrl()).header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(String.class));
        String value = response.path("access_token").asString("");
        if (value.isBlank()) {
            throw new ProviderException("PIX_TOKEN_INVALID", "PSP não devolveu access_token", true, null);
        }
        long expiresIn = response.path("expires_in").isNumber() ? response.path("expires_in").asLong() : 300;
        tokens.put(connection.accountId(), new Token(value, now.plusSeconds(expiresIn), connection.credentialsUpdatedAt()));
        return value;
    }

    private JsonNode call(PixConnection connection, String operation, Supplier<String> request) {
        // destino conferido a cada chamada (DNS atual), antes de enviar credenciais ou certificado
        endpoints.requireAllowed("oauth_token".equals(operation) ? connection.tokenUrl() : connection.apiBaseUrl());
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            String body = request.get();
            outcome = "success";
            return json.mapper().readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 404 && "find_pix".equals(operation)) {
                outcome = "not_found";
                throw new NotFound();
            }
            if (status.value() == 401) {
                tokens.remove(connection.accountId());
            }
            boolean transientFailure = status.value() == 429 || status.value() == 401 || status.is5xxServerError();
            outcome = transientFailure ? "transient" : "rejected";
            String code = "PIX_" + errorCode(e.getResponseBodyAsString(), status.value());
            LOG.atWarn().addKeyValue("provider", PROVIDER).addKeyValue("operation", operation)
                    .addKeyValue("status", status.value()).addKeyValue("code", code).log("PSP recusou a chamada da API Pix");
            throw new ProviderException(code, "API Pix HTTP " + status.value(), transientFailure, e);
        } catch (ResourceAccessException e) {
            outcome = "timeout";
            throw new ProviderException("PIX_UNAVAILABLE", "Falha de comunicação com o PSP", true, e);
        } finally {
            meterRegistry.counter("integration.calls", "provider", PROVIDER, "operation", operation, "outcome", outcome)
                    .increment();
            sample.stop(meterRegistry.timer("integration.calls.duration", "provider", PROVIDER, "operation", operation));
        }
    }

    private String errorCode(String body, int status) {
        try {
            JsonNode node = json.mapper().readTree(body == null || body.isBlank() ? "{}" : body);
            String type = node.path("type").asString("");
            if (!type.isBlank()) {
                String last = type.substring(type.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9_]", "");
                return last.isBlank() ? "HTTP_" + status : last.length() > 50 ? last.substring(0, 50) : last;
            }
        } catch (RuntimeException ignored) {
            // corpo não JSON: código pelo status
        }
        return "HTTP_" + status;
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new ProviderException("PIX_INVALID_RESPONSE", "Valor inválido na resposta do PSP", false, e);
        }
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 404 na consulta de Pix: o PSP não conhece a transação. */
    private static final class NotFound extends RuntimeException {
        NotFound() {
            super(null, null, false, false);
        }
    }
}
