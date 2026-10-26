package br.com.gestorcobranca.telephony.infrastructure.provider.alvaria;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider;
import br.com.gestorcobranca.telephony.domain.model.ProviderType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Adaptador da Alvaria CX (outbound/CTI da antiga Aspect). <b>Contrato da API a confirmar</b>
 * ({@link AlvariaApiContract}): o adaptador declara {@link Readiness#CONTRACT_PENDING} e só disca
 * com {@code app.telephony.homologation.providers} contendo {@code ALVARIA}.
 * <p>
 * Decisão 565: o discador do Gestor comanda (ritmo, porteiro do compliance por tentativa,
 * distribuição e tabulação); a Alvaria é a plataforma de voz que origina, detecta secretária,
 * conecta, grava e notifica. O discador/campanhas da Alvaria não são usados para cobrança porque
 * a tentativa precisa passar pelo porteiro imediatamente antes de cada discagem.
 * <p>
 * Mecânica:
 * <ul>
 *   <li>endereço da instância por conta ({@code apiBaseUrl}): HTTPS, sem usuário/porta incomum, no
 *       domínio permitido ({@code app.telephony.alvaria.allowed-host-suffixes}) — proteção contra
 *       SSRF, já que a assessoria informa o endereço;</li>
 *   <li>OAuth 2.0 <i>client credentials</i> ({@code clientId}/{@code clientSecret}); token em memória
 *       por conta até 60 s antes de vencer; 401 numa operação descarta o token e repete uma vez;</li>
 *   <li>timeouts explícitos (conexão 3 s, leitura 10 s); conexão recusada, tempo de conexão
 *       esgotado, 429 e 503 → {@code TransientProviderException.notProcessed} (o pedido não chegou a
 *       criar chamada: a contingência pode assumir); tempo de leitura esgotado e outros 5xx →
 *       transitório ambíguo (repete na mesma conta com a mesma {@code Idempotency-Key});</li>
 *   <li>4xx na discagem → {@code DialResult.rejected("ALVARIA_<código>")}; 4xx em conexão,
 *       encerramento ou gravação → {@code ProviderRejectedException};</li>
 *   <li>{@code integration.calls{provider=alvaria,operation,outcome}} e
 *       {@code integration.calls.duration}; nunca registra número, ramal, token ou segredo.</li>
 * </ul>
 */
@Component
class AlvariaTelephonyProvider implements TelephonyProvider {

    private static final Logger LOG = LoggerFactory.getLogger(AlvariaTelephonyProvider.class);
    static final String PROVIDER = "alvaria";
    private static final Duration TOKEN_SKEW = Duration.ofSeconds(60);

    private final RestClient client;
    private final JsonCodec json;
    private final MeterRegistry meterRegistry;
    private final TelephonyProperties.Alvaria config;
    private final Clock clock;
    private final Map<String, CachedToken> tokens = new ConcurrentHashMap<>();

    AlvariaTelephonyProvider(TelephonyProperties properties, JsonCodec json, MeterRegistry meterRegistry, Clock clock) {
        this.config = properties.alvaria();
        HttpClient http = HttpClient.newBuilder().connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(config.readTimeout());
        this.client = RestClient.builder().requestFactory(factory).build();
        this.json = json;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Override
    public ProviderType provider() {
        return ProviderType.ALVARIA;
    }

    @Override
    public Readiness readiness() {
        return Readiness.CONTRACT_PENDING;
    }

    @Override
    public void validateCredentials(Map<String, String> credentials) {
        baseUri(credentials.get("apiBaseUrl"));
    }

    @Override
    public DialResult originate(OriginateRequest request) {
        ProviderContext ctx = request.context();
        try {
            String response = call("originate", ctx, () -> post(ctx, AlvariaApiContract.ORIGINATE_PATH, Map.of(),
                    AlvariaApiContract.originate(request.callId(), request.agentEndpoint(), request.destination(),
                            ctx.callerId(), request.ringTimeoutSeconds(), request.record(), request.announceRecording()),
                    request.callId().toString()));
            String providerCallId = read(response).path(AlvariaApiContract.RESPONSE_CALL_ID).asString("");
            if (providerCallId.isBlank()) {
                throw new TransientProviderException("Resposta da Alvaria sem id da chamada");
            }
            return DialResult.accepted(request.callId(), providerCallId);
        } catch (Rejected e) {
            return DialResult.rejected(request.callId(), e.code, "HTTP " + e.status);
        }
    }

    @Override
    public List<DialResult> dialBatch(ProviderContext context, List<DialRequest> requests) {
        List<DialResult> results = new ArrayList<>(requests.size());
        for (int start = 0; start < requests.size(); start += AlvariaApiContract.BATCH_MAX) {
            List<DialRequest> chunk = requests.subList(start, Math.min(requests.size(), start + AlvariaApiContract.BATCH_MAX));
            try {
                results.addAll(dialChunk(context, chunk));
            } catch (TransientProviderException e) {
                if (start == 0) {
                    throw e;
                }
                // lotes anteriores já foram aceitos: relançar repetiria as chamadas deles; os restantes viram recusa
                // (o discador tenta de novo pela política de retentativas)
                LOG.atWarn().addKeyValue("provider", PROVIDER).addKeyValue("pending", requests.size() - start)
                        .log("Falha transitória num lote parcial da Alvaria; itens restantes recusados");
                requests.subList(start, requests.size()).forEach(r ->
                        results.add(DialResult.rejected(r.callId(), "ALVARIA_BATCH_INTERRUPTED", "Lote interrompido")));
                return results;
            }
        }
        return results;
    }

    private List<DialResult> dialChunk(ProviderContext ctx, List<DialRequest> chunk) {
        String key = batchKey(chunk);
        try {
            String response = call("dial_batch", ctx, () -> post(ctx, AlvariaApiContract.BATCH_PATH, Map.of(),
                    AlvariaApiContract.batch(chunk, ctx.callerId()), key));
            Map<String, JsonNode> byReference = new LinkedHashMap<>();
            for (JsonNode node : read(response).path(AlvariaApiContract.RESPONSE_RESULTS)) {
                byReference.put(node.path(AlvariaApiContract.RESULT_REFERENCE).asString(""), node);
            }
            return chunk.stream().map(r -> {
                JsonNode node = byReference.get(r.callId().toString());
                if (node == null) {
                    return DialResult.rejected(r.callId(), "ALVARIA_NO_RESULT", "Sem resultado para o pedido");
                }
                String providerCallId = node.path(AlvariaApiContract.RESULT_CALL_ID).asString("");
                if (node.path(AlvariaApiContract.RESULT_ACCEPTED).asBoolean(false) && !providerCallId.isBlank()) {
                    return DialResult.accepted(r.callId(), providerCallId);
                }
                return DialResult.rejected(r.callId(), "ALVARIA_" + code(node.path(AlvariaApiContract.RESULT_ERROR_CODE)
                        .asString("")), "Recusado pela Alvaria");
            }).toList();
        } catch (Rejected e) {
            return chunk.stream().map(r -> DialResult.rejected(r.callId(), e.code, "HTTP " + e.status)).toList();
        }
    }

    @Override
    public void bridge(BridgeRequest request) {
        ProviderContext ctx = request.context();
        rejectedAsException(() -> call("bridge", ctx, () -> post(ctx, AlvariaApiContract.BRIDGE_PATH,
                Map.of("providerCallId", request.providerCallId()),
                AlvariaApiContract.bridge(request.callId(), request.agentEndpoint()), null)));
    }

    @Override
    public void hangup(HangupRequest request) {
        if (request.providerCallId() == null || request.providerCallId().isBlank()) {
            return;
        }
        ProviderContext ctx = request.context();
        rejectedAsException(() -> call("hangup", ctx, () -> post(ctx, AlvariaApiContract.HANGUP_PATH,
                Map.of("providerCallId", request.providerCallId()),
                AlvariaApiContract.hangup(request.callId(), request.reason()), null)));
    }

    @Override
    public RecordingMedia fetchRecording(ProviderContext context, String recordingRef) {
        return rejectedAsException(() -> call("fetch_recording", context, () -> {
            URI uri = uri(context, AlvariaApiContract.RECORDING_PATH, Map.of("recordingRef", recordingRef));
            return client.get().uri(uri).header("Authorization", "Bearer " + token(context))
                    .exchange((req, res) -> media(res), false);
        }));
    }

    @Override
    public void deleteRecording(ProviderContext context, String recordingRef) {
        rejectedAsException(() -> call("delete_recording", context, () -> {
            URI uri = uri(context, AlvariaApiContract.RECORDING_PATH, Map.of("recordingRef", recordingRef));
            client.delete().uri(uri).header("Authorization", "Bearer " + token(context)).retrieve().toBodilessEntity();
            return null;
        }));
    }

    // ------------------------------------------------------------------ HTTP

    private String post(ProviderContext ctx, String path, Map<String, String> variables, Object body, String idempotencyKey) {
        RestClient.RequestBodySpec spec = client.post().uri(uri(ctx, path, variables))
                .header("Authorization", "Bearer " + token(ctx))
                .contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            spec = spec.header(AlvariaApiContract.IDEMPOTENCY_HEADER, idempotencyKey);
        }
        return spec.body(json.toJson(body)).retrieve().body(String.class);
    }

    private RecordingMedia media(ClientHttpResponse res) throws IOException {
        int status = res.getStatusCode().value();
        if (status != 200) {
            byte[] errorBody = res.getBody().readNBytes(4096);
            res.close();
            // erro como o do retrieve(): 401 renova o token; os demais são classificados em call()
            throw new RestClientResponseException("Alvaria HTTP " + status, status, "", null, errorBody,
                    StandardCharsets.UTF_8);
        }
        MediaType type = res.getHeaders().getContentType();
        long size = res.getHeaders().getContentLength();
        InputStream body = res.getBody();
        return new RecordingMedia(type == null ? null : type.getType() + "/" + type.getSubtype(), size,
                new FilterInputStream(body) {
                    @Override
                    public void close() throws IOException {
                        try {
                            super.close();
                        } finally {
                            res.close();
                        }
                    }
                });
    }

    /**
     * Executa com métrica e classificação de erros; 401 descarta o token e repete uma vez (token
     * revogado ou rotação de credencial).
     */
    private <T> T call(String operation, ProviderContext ctx, Supplier<T> action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            T result;
            try {
                result = action.get();
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() != 401) {
                    throw e;
                }
                tokens.remove(tokenKey(ctx));
                result = action.get();
            }
            outcome = "success";
            return result;
        } catch (RestClientResponseException e) {
            RuntimeException mapped = statusError(e.getStatusCode().value(), e.getResponseBodyAsString());
            outcome = mapped instanceof Rejected ? "rejected" : "transient";
            throw mapped;
        } catch (ResourceAccessException e) {
            boolean notProcessed = notProcessed(e);
            outcome = notProcessed ? "unreachable" : "timeout";
            throw notProcessed ? TransientProviderException.notProcessed("Alvaria inacessível", e)
                    : new TransientProviderException("Tempo esgotado na Alvaria", e);
        } catch (Rejected | TransientProviderException e) {
            outcome = e instanceof Rejected ? "rejected" : "transient";
            throw e;
        } finally {
            meterRegistry.counter("integration.calls", "provider", PROVIDER, "operation", operation, "outcome", outcome)
                    .increment();
            sample.stop(meterRegistry.timer("integration.calls.duration", "provider", PROVIDER, "operation", operation));
        }
    }

    private RuntimeException statusError(int status, String body) {
        if (status == 429 || status == 503) {
            return TransientProviderException.notProcessed("Alvaria HTTP " + status, null);
        }
        if (status >= 500) {
            return new TransientProviderException("Alvaria HTTP " + status);
        }
        String code = errorCode(body);
        LOG.atWarn().addKeyValue("provider", PROVIDER).addKeyValue("status", status).addKeyValue("errorCode", code)
                .log("Alvaria recusou o pedido");
        return new Rejected(status, "ALVARIA_" + code);
    }

    private static boolean notProcessed(ResourceAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException
                    || t instanceof UnknownHostException) {
                return true;
            }
        }
        return false;
    }

    private <T> T rejectedAsException(Supplier<T> action) {
        try {
            return action.get();
        } catch (Rejected e) {
            throw new ProviderRejectedException(e.code, "Alvaria recusou o pedido (HTTP " + e.status + ")");
        }
    }

    // ------------------------------------------------------------------ token OAuth 2.0

    private String token(ProviderContext ctx) {
        String key = tokenKey(ctx);
        Instant now = Instant.now(clock);
        CachedToken cached = tokens.get(key);
        if (cached != null && cached.expiresAt().isAfter(now.plus(TOKEN_SKEW))) {
            return cached.value();
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", ctx.credentials().get("clientId"));
        form.add("client_secret", ctx.credentials().get("clientSecret"));
        String response;
        try {
            response = client.post().uri(uri(ctx, AlvariaApiContract.TOKEN_PATH, Map.of()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(String.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            meterRegistry.counter("integration.calls", "provider", PROVIDER, "operation", "oauth_token", "outcome",
                    status >= 500 || status == 429 ? "transient" : "rejected").increment();
            if (status >= 500 || status == 429) {
                throw TransientProviderException.notProcessed("Alvaria (token) HTTP " + status, e);
            }
            throw new Rejected(status, "ALVARIA_AUTH_FAILED");
        } catch (ResourceAccessException e) {
            meterRegistry.counter("integration.calls", "provider", PROVIDER, "operation", "oauth_token", "outcome",
                    "unreachable").increment();
            throw TransientProviderException.notProcessed("Alvaria (token) inacessível", e);
        }
        JsonNode node = read(response);
        String value = node.path("access_token").asString("");
        if (value.isBlank()) {
            throw TransientProviderException.notProcessed("Resposta de token da Alvaria sem access_token", null);
        }
        long expiresIn = Math.max(60, node.path("expires_in").asLong(300));
        tokens.put(key, new CachedToken(value, now.plusSeconds(expiresIn)));
        meterRegistry.counter("integration.calls", "provider", PROVIDER, "operation", "oauth_token", "outcome", "success")
                .increment();
        return value;
    }

    /** Conta + digest do cliente: rotação de credencial invalida o token em cache. */
    private static String tokenKey(ProviderContext ctx) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((ctx.credentials().getOrDefault("clientId", "")
                    + "\n" + ctx.credentials().getOrDefault("clientSecret", "") + "\n"
                    + ctx.credentials().getOrDefault("apiBaseUrl", "")).getBytes(StandardCharsets.UTF_8));
            return ctx.accountId() + ":" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record CachedToken(String value, Instant expiresAt) {
    }

    // ------------------------------------------------------------------ endereço

    private URI uri(ProviderContext ctx, String path, Map<String, String> variables) {
        URI base = baseUri(ctx.credentials().get("apiBaseUrl"));
        String resolved = path;
        for (Map.Entry<String, String> v : variables.entrySet()) {
            resolved = resolved.replace("{" + v.getKey() + "}", encodeSegment(v.getValue()));
        }
        String basePath = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$", "");
        return URI.create(base.getScheme() + "://" + base.getRawAuthority() + basePath + resolved);
    }

    private static String encodeSegment(String value) {
        if (value == null || value.isBlank()) {
            throw new Rejected(400, "ALVARIA_MISSING_REFERENCE");
        }
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** {@code apiBaseUrl} validado: HTTPS (ou HTTP só com a opção de teste), domínio permitido, sem usuário. */
    URI baseUri(String value) {
        if (value == null || value.isBlank() || value.length() > 300) {
            throw invalidUrl();
        }
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException e) {
            throw invalidUrl();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("https".equals(scheme) || ("http".equals(scheme) && config.allowInsecureHttp()))) {
            throw new BusinessRuleException("telephony_account.alvaria_url_insecure",
                    "O endereço da API da Alvaria precisa usar HTTPS");
        }
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw invalidUrl();
        }
        String host = IDN.toASCII(uri.getHost()).toLowerCase(Locale.ROOT);
        boolean allowed = config.allowedHostSuffixes().stream().map(s -> s.toLowerCase(Locale.ROOT).trim())
                .anyMatch(s -> s.startsWith(".") ? host.endsWith(s) && host.length() > s.length() : host.equals(s));
        if (!allowed) {
            throw new BusinessRuleException("telephony_account.alvaria_host_not_allowed",
                    "Domínio da API da Alvaria não permitido: " + host);
        }
        return uri;
    }

    private static BusinessRuleException invalidUrl() {
        return new BusinessRuleException("telephony_account.alvaria_url_invalid", "Endereço da API da Alvaria inválido");
    }

    // ------------------------------------------------------------------ utilitários

    private JsonNode read(String body) {
        try {
            return json.mapper().readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (RuntimeException e) {
            throw new TransientProviderException("Resposta ilegível da Alvaria", e);
        }
    }

    private String errorCode(String body) {
        try {
            String code = json.mapper().readTree(body == null || body.isBlank() ? "{}" : body)
                    .path(AlvariaApiContract.ERROR_CODE).asString("");
            return code(code);
        } catch (RuntimeException e) {
            return "UNKNOWN";
        }
    }

    /** Código estável e curto para a chamada/métrica (nunca o texto livre do fornecedor). */
    private static String code(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        return code.isEmpty() ? "UNKNOWN" : code.substring(0, Math.min(40, code.length()));
    }

    private static String batchKey(List<DialRequest> chunk) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            chunk.stream().map(DialRequest::callId).map(UUID::toString).sorted()
                    .forEach(id -> digest.update(id.getBytes(StandardCharsets.US_ASCII)));
            return "batch-" + HexFormat.of().formatHex(digest.digest(), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Recusa 4xx interna ao adaptador (vira {@code DialResult.rejected} ou {@code ProviderRejectedException}). */
    private static final class Rejected extends RuntimeException {

        private final int status;
        private final String code;

        Rejected(int status, String code) {
            super(code, null, false, false);
            this.status = status;
            this.code = code;
        }
    }
}
