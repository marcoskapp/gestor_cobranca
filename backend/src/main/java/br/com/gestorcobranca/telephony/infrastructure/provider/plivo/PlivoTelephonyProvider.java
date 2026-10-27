package br.com.gestorcobranca.telephony.infrastructure.provider.plivo;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider;
import br.com.gestorcobranca.telephony.domain.model.ProviderType;
import br.com.gestorcobranca.telephony.infrastructure.provider.ProviderHttp;
import br.com.gestorcobranca.telephony.infrastructure.provider.ProviderHttp.Rejected;
import br.com.gestorcobranca.telephony.infrastructure.provider.WebhookForms;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Adaptador da <b>Plivo Voice</b> (API pública; anexo D de {@code docs/tecnico/desenho/telefonia.md}).
 * {@link Readiness#READY}, ou {@link Readiness#DISABLED} com {@code app.telephony.plivo.enabled=false}.
 * <ul>
 *   <li>HTTP Basic com Auth ID e Auth Token; {@code https://api.plivo.com/v1/Account/{auth_id}/…};</li>
 *   <li>{@code originate}: {@code POST /Call/} para o ramal do operador (endpoint SIP da Plivo ou
 *       telefone) com {@code answer_url} que, atendida pelo operador, devolve {@code <Dial>} para o
 *       devedor (o número vai no corpo da resposta, buscado da chamada; nunca em URL);</li>
 *   <li>{@code dialBatch}: um {@code POST /Call/} por item com {@code machine_detection=true}
 *       assíncrono ({@code machine_detection_url}) — a resposta do atendimento grava a sessão, toca o
 *       aviso e espera o tempo da detecção; sem veredito de máquina, a chamada volta a pedir
 *       instruções ({@code <Redirect>}) e é tratada como atendida por pessoa;</li>
 *   <li>a criação devolve só {@code request_uuid}; o {@code CallUUID} chega nos eventos e é guardado
 *       na chamada ({@code providerLegId}) para {@code bridge} (transferência da perna A para a URL de
 *       conexão ao operador) e {@code hangup} ({@code DELETE /Call/{call_uuid}/}; antes de conhecer o
 *       {@code CallUUID}, {@code DELETE /Request/{request_uuid}/}). <b>Nunca</b> {@code DELETE /Call/}
 *       sem id (encerraria todas as chamadas da conta);</li>
 *   <li>gravações: {@code GET /Recording/{id}/} → {@code recording_url} (HTTPS e host em
 *       {@code app.telephony.plivo.recording-hosts}), download com HTTP Basic e
 *       {@code DELETE /Recording/{id}/};</li>
 *   <li>tempo de leitura esgotado ou 5xx (exceto 503) na criação → recusa {@code PLIVO_UNCONFIRMED}
 *       (sem repetição: a Plivo não deduplica pedidos).</li>
 * </ul>
 */
@Component
class PlivoTelephonyProvider implements TelephonyProvider {

    private static final Logger LOG = LoggerFactory.getLogger(PlivoTelephonyProvider.class);
    static final String METRIC = "plivo";

    static final Pattern AUTH_ID = Pattern.compile("[MS]A[A-Za-z0-9]{18}");
    private static final Pattern AUTH_TOKEN = Pattern.compile("[A-Za-z0-9]{16,128}");
    static final Pattern PLIVO_UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern RECORDING_ID = Pattern.compile("[0-9a-zA-Z\\-]{8,64}");
    private static final Pattern SIP = Pattern.compile("sip:[A-Za-z][A-Za-z0-9_.\\-]{0,50}@[A-Za-z0-9.\\-]{3,60}");
    private static final Pattern E164 = Pattern.compile("\\+?[1-9]\\d{7,14}");

    private final ProviderHttp http;
    private final JsonCodec json;
    private final TelephonyProperties.Plivo config;

    PlivoTelephonyProvider(TelephonyProperties properties, JsonCodec json, MeterRegistry meterRegistry) {
        this.config = properties.plivo();
        this.json = json;
        this.http = new ProviderHttp(METRIC, config.apiBaseUrl(), config.allowedHosts(), config.allowInsecureHttp(),
                config.connectTimeout(), config.readTimeout(), meterRegistry, PlivoTelephonyProvider::errorCode);
    }

    @Override
    public ProviderType provider() {
        return ProviderType.PLIVO;
    }

    @Override
    public Readiness readiness() {
        return config.enabled() ? Readiness.READY : Readiness.DISABLED;
    }

    @Override
    public void validateCredentials(Map<String, String> credentials) {
        String authId = credentials.get("authId");
        if (authId == null || !AUTH_ID.matcher(authId.trim()).matches()) {
            throw new BusinessRuleException("telephony_account.plivo_credentials_invalid",
                    "Credencial da Plivo inválida: Auth ID (MA ou SA seguido de 18 caracteres)");
        }
        String token = credentials.get("authToken");
        if (token == null || !AUTH_TOKEN.matcher(token.trim()).matches()) {
            throw new BusinessRuleException("telephony_account.plivo_credentials_invalid",
                    "Credencial da Plivo inválida: Auth Token");
        }
    }

    @Override
    public void validateEndpoint(String extension) {
        if (!validEndpoint(extension)) {
            throw new BusinessRuleException("agent_endpoint.plivo_invalid",
                    "Ramal da Plivo: sip:usuario@phone.plivo.com (endpoint da Plivo) ou +55…");
        }
    }

    // ------------------------------------------------------------------ discagem

    @Override
    public DialResult originate(OriginateRequest request) {
        ProviderContext ctx = request.context();
        if (!validEndpoint(request.agentEndpoint())) {
            return DialResult.rejected(request.callId(), "PLIVO_INVALID_ENDPOINT", "Ramal fora do formato da Plivo");
        }
        String ref = request.callId().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", PlivoXml.digits(ctx.callerId()));
        body.put("to", endpointAddress(request.agentEndpoint()));
        body.put("answer_url", url(ctx, "answer", ref, "agent", null));
        body.put("answer_method", "POST");
        body.put("hangup_url", url(ctx, "hangup", ref, "agent", null));
        body.put("hangup_method", "POST");
        body.put("ring_timeout", request.ringTimeoutSeconds());
        return create("originate", ctx, request.callId(), body);
    }

    @Override
    public List<DialResult> dialBatch(ProviderContext context, List<DialRequest> requests) {
        List<DialResult> results = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            DialRequest r = requests.get(i);
            try {
                results.add(dial(context, r));
            } catch (TransientProviderException e) {
                if (i == 0) {
                    throw e;
                }
                LOG.atWarn().addKeyValue("provider", METRIC).addKeyValue("pending", requests.size() - i)
                        .log("Falha transitória num lote parcial da Plivo; itens restantes recusados");
                requests.subList(i, requests.size()).forEach(p ->
                        results.add(DialResult.rejected(p.callId(), "PLIVO_BATCH_INTERRUPTED", "Lote interrompido")));
                return results;
            }
        }
        return results;
    }

    private DialResult dial(ProviderContext ctx, DialRequest r) {
        String ref = r.callId().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", PlivoXml.digits(ctx.callerId()));
        body.put("to", PlivoXml.digits(r.destination()));
        body.put("answer_url", url(ctx, "answer", ref, "dialer", r.detectMachine() ? "1" : "0"));
        body.put("answer_method", "POST");
        body.put("ring_url", url(ctx, "ring", ref, "dialer", null));
        body.put("ring_method", "POST");
        body.put("hangup_url", url(ctx, "hangup", ref, "dialer", null));
        body.put("hangup_method", "POST");
        body.put("ring_timeout", r.ringTimeoutSeconds());
        if (r.detectMachine()) {
            body.put("machine_detection", "true");
            body.put("machine_detection_time", amdMillis());
            body.put("machine_detection_url", url(ctx, "amd", ref, null, null));
            body.put("machine_detection_method", "POST");
        }
        return create("dial", ctx, r.callId(), body);
    }

    /** {@code machine_detection_time} (ms, 2.000–10.000). */
    int amdMillis() {
        return (int) Math.max(2000, Math.min(10_000, config.amdTime().toMillis()));
    }

    private DialResult create(String operation, ProviderContext ctx, UUID callId, Map<String, Object> body) {
        try {
            String response = http.call(operation, () -> http.client().post().uri(http.uri(account(ctx) + "/Call/"))
                    .header("Authorization", auth(ctx)).contentType(MediaType.APPLICATION_JSON)
                    .body(json.toJson(body)).retrieve().body(String.class));
            String requestUuid = read(response).path("request_uuid").asString("");
            if (!PLIVO_UUID.matcher(requestUuid).matches()) {
                return DialResult.rejected(callId, "PLIVO_NO_REQUEST_UUID", "Resposta sem request_uuid");
            }
            return DialResult.accepted(callId, requestUuid);
        } catch (Rejected e) {
            return DialResult.rejected(callId, e.code(), "HTTP " + e.status());
        } catch (TransientProviderException e) {
            if (e.notProcessed()) {
                throw e;
            }
            LOG.atWarn().addKeyValue("provider", METRIC).addKeyValue("callId", callId)
                    .log("Criação de chamada na Plivo não confirmada; não será repetida");
            return DialResult.rejected(callId, "PLIVO_UNCONFIRMED", "Criação não confirmada");
        }
    }

    // ------------------------------------------------------------------ chamada em andamento

    /** Transferência da perna A para a URL que devolve {@code <Dial>} ao ramal do operador escolhido. */
    @Override
    public void bridge(BridgeRequest request) {
        ProviderContext ctx = request.context();
        String callUuid = uuid(request.providerLegId(), "PLIVO_MISSING_CALL_UUID");
        if (request.operatorId() == null) {
            throw new ProviderRejectedException("PLIVO_MISSING_OPERATOR", "Operador da conexão ausente");
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put(WebhookForms.KIND, "bridge");
        query.put(WebhookForms.REFERENCE, request.callId().toString());
        query.put(WebhookForms.OPERATOR, request.operatorId().toString());
        transfer("bridge", ctx, callUuid, WebhookForms.callback(ctx.webhookUrl(), query));
    }

    @Override
    public void hangup(HangupRequest request) {
        ProviderContext ctx = request.context();
        if (request.providerLegId() != null && PLIVO_UUID.matcher(request.providerLegId()).matches()) {
            if (request.reason() == HangupReason.ABANDONED) {
                transfer("hangup", ctx, request.providerLegId(), url(ctx, "abandon", request.callId().toString(), null, null));
                return;
            }
            delete("hangup", ctx, account(ctx) + "/Call/" + request.providerLegId() + "/");
            return;
        }
        if (request.providerCallId() == null || !PLIVO_UUID.matcher(request.providerCallId()).matches()) {
            // sem id válido nada é pedido (DELETE /Call/ sem id encerraria todas as chamadas da conta)
            return;
        }
        // ainda sem CallUUID (na fila ou tocando): cancela o pedido de discagem
        delete("hangup", ctx, account(ctx) + "/Request/" + request.providerCallId() + "/");
    }

    private void transfer(String operation, ProviderContext ctx, String callUuid, String alegUrl) {
        Map<String, Object> body = Map.of("legs", "aleg", "aleg_url", alegUrl, "aleg_method", "POST");
        http.rejectedAsException(() -> http.call(operation, () -> http.client().post()
                .uri(http.uri(account(ctx) + "/Call/" + callUuid + "/")).header("Authorization", auth(ctx))
                .contentType(MediaType.APPLICATION_JSON).body(json.toJson(body)).retrieve().toBodilessEntity()));
    }

    private void delete(String operation, ProviderContext ctx, String path) {
        http.rejectedAsException(() -> http.call(operation, () -> http.client().delete().uri(http.uri(path))
                .header("Authorization", auth(ctx)).retrieve().toBodilessEntity()));
    }

    // ------------------------------------------------------------------ gravações

    @Override
    public RecordingMedia fetchRecording(ProviderContext context, String recordingRef) {
        String id = recordingId(recordingRef);
        return http.rejectedAsException(() -> {
            String response = http.call("fetch_recording", () -> http.client().get()
                    .uri(http.uri(account(context) + "/Recording/" + id + "/")).header("Authorization", auth(context))
                    .retrieve().body(String.class));
            URI media = recordingUri(read(response).path("recording_url").asString(""));
            return http.call("fetch_recording_media", () -> http.client().get().uri(media)
                    .header("Authorization", auth(context))
                    .exchange((req, res) -> ProviderHttp.media(res, "Plivo"), false));
        });
    }

    @Override
    public void deleteRecording(ProviderContext context, String recordingRef) {
        delete("delete_recording", context, account(context) + "/Recording/" + recordingId(recordingRef) + "/");
    }

    /** {@code recording_url} da Plivo validado: HTTPS (HTTP só no teste) e host permitido (anti-SSRF). */
    URI recordingUri(String value) {
        URI uri;
        try {
            uri = new URI(value == null ? "" : value.trim());
        } catch (URISyntaxException e) {
            throw new ProviderRejectedException("PLIVO_RECORDING_URL_INVALID", "Endereço da gravação inválido");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean schemeOk = "https".equals(scheme) || ("http".equals(scheme) && config.allowInsecureHttp());
        if (!schemeOk || uri.getRawUserInfo() != null || !ProviderHttp.hostAllowed(uri.getHost(), config.recordingHosts())) {
            throw new ProviderRejectedException("PLIVO_RECORDING_HOST_NOT_ALLOWED", "Host da gravação não permitido");
        }
        return uri;
    }

    // ------------------------------------------------------------------ verificação

    /** {@code GET /v1/Account/{auth_id}/}: leitura dos dados da própria conta; não cria nada nem liga. */
    @Override
    public boolean verifiesCredentials() {
        return true;
    }

    @Override
    public CredentialCheck verifyCredentials(ProviderContext context) {
        try {
            http.call("verify_credentials", () -> http.client().get().uri(http.uri(account(context) + "/"))
                    .header("Authorization", auth(context)).retrieve().toBodilessEntity());
            return CredentialCheck.passed();
        } catch (Rejected e) {
            return CredentialCheck.failed(e.status() == 401 || e.status() == 403 ? "PLIVO_AUTH_REJECTED" : e.code());
        } catch (TransientProviderException e) {
            return CredentialCheck.failed("UNREACHABLE");
        }
    }

    // ------------------------------------------------------------------ utilitários

    private String url(ProviderContext ctx, String kind, String ref, String leg, String amd) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put(WebhookForms.KIND, kind);
        query.put(WebhookForms.REFERENCE, ref);
        query.put(WebhookForms.LEG, leg);
        query.put(WebhookForms.AMD, amd);
        return WebhookForms.callback(ctx.webhookUrl(), query);
    }

    private static String account(ProviderContext ctx) {
        String authId = ctx.credentials().get("authId");
        if (authId == null || !AUTH_ID.matcher(authId).matches()) {
            throw new ProviderRejectedException("PLIVO_INVALID_ACCOUNT", "Auth ID inválido");
        }
        return "/v1/Account/" + authId;
    }

    private static String auth(ProviderContext ctx) {
        String user = ctx.credentials().getOrDefault("authId", "");
        String password = ctx.credentials().getOrDefault("authToken", "");
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static String uuid(String value, String code) {
        if (value == null || !PLIVO_UUID.matcher(value).matches()) {
            throw new ProviderRejectedException(code, "Identificador da chamada na Plivo ausente ou inválido");
        }
        return value;
    }

    private static String recordingId(String value) {
        if (value == null || !RECORDING_ID.matcher(value).matches()) {
            throw new ProviderRejectedException("PLIVO_INVALID_RECORDING", "Id da gravação inválido");
        }
        return value;
    }

    private static boolean validEndpoint(String endpoint) {
        String value = endpoint == null ? "" : endpoint.trim();
        return SIP.matcher(value).matches() || E164.matcher(value).matches();
    }

    /** Destino do {@code to}: URI SIP como está; telefone em dígitos. */
    private static String endpointAddress(String endpoint) {
        String value = endpoint.trim();
        return value.toLowerCase(Locale.ROOT).startsWith("sip:") ? value : PlivoXml.digits(value);
    }

    private JsonNode read(String body) {
        try {
            return json.mapper().readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (RuntimeException e) {
            throw new TransientProviderException("Resposta ilegível da Plivo", e);
        }
    }

    /** O corpo de erro da Plivo traz texto livre ({@code error}); o código estável é o status HTTP. */
    private static String errorCode(Integer status, String body) {
        LOG.atWarn().addKeyValue("provider", METRIC).addKeyValue("status", status).log("Plivo recusou o pedido");
        return "PLIVO_HTTP_" + status;
    }
}
