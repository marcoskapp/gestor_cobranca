package br.com.gestorcobranca.telephony.infrastructure.provider.twilio;

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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Adaptador da <b>Twilio Programmable Voice</b> (API pública; anexo C de
 * {@code docs/tecnico/desenho/telefonia.md}). {@link Readiness#READY}, ou
 * {@link Readiness#DISABLED} com {@code app.telephony.twilio.enabled=false}.
 * <ul>
 *   <li>autenticação HTTP Basic com a <b>API Key</b> (SID e segredo) da conta; o Auth Token só
 *       valida os webhooks e a verificação de credenciais;</li>
 *   <li>{@code originate}: {@code POST /2010-04-01/Accounts/{AccountSid}/Calls.json} para o ramal do
 *       operador ({@code client:}, {@code sip:} ou E.164) com TwiML inline que disca para o devedor
 *       ({@code <Dial><Number>}, eventos da perna do devedor e aviso de gravação pelo {@code url});</li>
 *   <li>{@code dialBatch}: uma chamada por item (a API não tem lote), {@code MachineDetection=Enable}
 *       com AMD <b>assíncrono</b> ({@code AsyncAmd=true}, veredito em {@code AsyncAmdStatusCallback}),
 *       gravação em dois canais e TwiML inline de espera;</li>
 *   <li>{@code bridge}: atualização da chamada com TwiML {@code <Dial>} para o ramal;
 *       {@code hangup}: {@code Status=completed}, ou TwiML com a mensagem de abandono;</li>
 *   <li>gravações: {@code GET …/Recordings/{Sid}.wav?RequestedChannels=2} e {@code DELETE
 *       …/Recordings/{Sid}.json};</li>
 *   <li>tempo de leitura esgotado ou 5xx (exceto 503) na criação de chamada é tratado como
 *       <b>recusa</b> ({@code TWILIO_UNCONFIRMED}): a API não deduplica pedidos e repetir poderia
 *       ligar duas vezes para o devedor (a tentativa no compliance já foi registrada); só falhas que
 *       comprovadamente não criaram a chamada (conexão, 429, 503) seguem para nova tentativa ou
 *       contingência;</li>
 *   <li>callbacks por chamada no webhook da conta com referências opacas na consulta
 *       ({@code k}, {@code ref}, {@code leg}, {@code amd}); nunca número ou nome em URL ou log.</li>
 * </ul>
 */
@Component
class TwilioTelephonyProvider implements TelephonyProvider {

    private static final Logger LOG = LoggerFactory.getLogger(TwilioTelephonyProvider.class);
    static final String METRIC = "twilio";
    private static final String API = "/2010-04-01/Accounts/";

    static final Pattern ACCOUNT_SID = Pattern.compile("AC[0-9a-fA-F]{32}");
    static final Pattern API_KEY_SID = Pattern.compile("SK[0-9a-fA-F]{32}");
    static final Pattern APP_SID = Pattern.compile("AP[0-9a-fA-F]{32}");
    static final Pattern CALL_SID = Pattern.compile("CA[0-9a-fA-F]{32}");
    static final Pattern RECORDING_SID = Pattern.compile("RE[0-9a-fA-F]{32}");
    private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9]{16,128}");
    static final Pattern CLIENT = Pattern.compile("client:([A-Za-z0-9_]{1,100})");
    private static final Pattern SIP = Pattern.compile("sip:[A-Za-z0-9_.+\\-]{1,40}@[A-Za-z0-9.\\-]{3,60}(:\\d{2,5})?");
    private static final Pattern E164 = Pattern.compile("\\+[1-9]\\d{7,14}");

    private final ProviderHttp http;
    private final JsonCodec json;
    private final TwilioTwiml twiml;
    private final TelephonyProperties.Twilio config;
    private final Clock clock;

    TwilioTelephonyProvider(TelephonyProperties properties, JsonCodec json, TwilioTwiml twiml,
                            MeterRegistry meterRegistry, Clock clock) {
        this.config = properties.twilio();
        this.json = json;
        this.twiml = twiml;
        this.clock = clock;
        this.http = new ProviderHttp(METRIC, config.apiBaseUrl(), config.allowedHosts(), config.allowInsecureHttp(),
                config.connectTimeout(), config.readTimeout(), meterRegistry, this::errorCode);
    }

    @Override
    public ProviderType provider() {
        return ProviderType.TWILIO;
    }

    @Override
    public Readiness readiness() {
        return config.enabled() ? Readiness.READY : Readiness.DISABLED;
    }

    @Override
    public void validateCredentials(Map<String, String> credentials) {
        require(credentials.get("accountSid"), ACCOUNT_SID, "Account SID (AC seguido de 32 caracteres hexadecimais)");
        require(credentials.get("apiKeySid"), API_KEY_SID, "API Key SID (SK seguido de 32 caracteres hexadecimais)");
        require(credentials.get("apiKeySecret"), SECRET, "segredo da API Key");
        require(credentials.get("authToken"), SECRET, "Auth Token");
        String app = credentials.get("twimlAppSid");
        if (app != null && !app.isBlank()) {
            require(app, APP_SID, "TwiML App SID (AP seguido de 32 caracteres hexadecimais)");
        }
    }

    @Override
    public void validateEndpoint(String extension) {
        String value = extension == null ? "" : extension.trim();
        if (!(CLIENT.matcher(value).matches() || SIP.matcher(value).matches() || E164.matcher(value).matches())) {
            throw new BusinessRuleException("agent_endpoint.twilio_invalid",
                    "Ramal da Twilio: client:<identidade> (softphone no navegador), sip:usuario@dominio ou +55…");
        }
    }

    // ------------------------------------------------------------------ discagem

    @Override
    public DialResult originate(OriginateRequest request) {
        ProviderContext ctx = request.context();
        if (!validEndpoint(request.agentEndpoint())) {
            return DialResult.rejected(request.callId(), "TWILIO_INVALID_ENDPOINT", "Ramal fora do formato da Twilio");
        }
        String ref = request.callId().toString();
        String body = twiml.originate(request.destination(), ctx.callerId(), request.ringTimeoutSeconds(), request.record(),
                url(ctx, "status", ref, "customer", null), url(ctx, "recording", ref, null, null),
                request.announceRecording() ? url(ctx, "announce", ref, null, null) : null);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", request.agentEndpoint().trim());
        form.add("From", ctx.callerId());
        form.add("Twiml", body);
        form.add("Timeout", String.valueOf(request.ringTimeoutSeconds()));
        form.add("StatusCallback", url(ctx, "status", ref, "agent", null));
        form.add("StatusCallbackEvent", "completed");
        return create("originate", ctx, request.callId(), form);
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
                // chamadas anteriores já foram criadas: relançar repetiria as delas; as restantes viram recusa
                LOG.atWarn().addKeyValue("provider", METRIC).addKeyValue("pending", requests.size() - i)
                        .log("Falha transitória num lote parcial da Twilio; itens restantes recusados");
                requests.subList(i, requests.size()).forEach(p ->
                        results.add(DialResult.rejected(p.callId(), "TWILIO_BATCH_INTERRUPTED", "Lote interrompido")));
                return results;
            }
        }
        return results;
    }

    private DialResult dial(ProviderContext ctx, DialRequest r) {
        String ref = r.callId().toString();
        int amdSeconds = (int) Math.max(3, Math.min(59, config.amdTimeout().toSeconds()));
        int hold = (r.detectMachine() ? amdSeconds : 0) + 20;
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", r.destination());
        form.add("From", ctx.callerId());
        form.add("Twiml", twiml.dialerHold(r.announceRecording(), hold));
        form.add("Timeout", String.valueOf(r.ringTimeoutSeconds()));
        form.add("StatusCallback", url(ctx, "status", ref, "dialer", r.detectMachine() ? "1" : "0"));
        for (String event : List.of("ringing", "answered", "completed")) {
            form.add("StatusCallbackEvent", event);
        }
        if (r.detectMachine()) {
            form.add("MachineDetection", "Enable");
            form.add("AsyncAmd", "true");
            form.add("AsyncAmdStatusCallback", url(ctx, "amd", ref, null, null));
            form.add("MachineDetectionTimeout", String.valueOf(amdSeconds));
        }
        if (r.record()) {
            form.add("Record", "true");
            form.add("RecordingChannels", "dual");
            form.add("RecordingStatusCallback", url(ctx, "recording", ref, null, null));
            form.add("RecordingStatusCallbackEvent", "completed");
        }
        return create("dial", ctx, r.callId(), form);
    }

    private DialResult create(String operation, ProviderContext ctx, UUID callId, MultiValueMap<String, String> form) {
        if (form.getFirst("Twiml").length() > TwilioTwiml.MAX_INLINE) {
            return DialResult.rejected(callId, "TWILIO_TWIML_TOO_LONG", "Instruções acima de 4.000 caracteres");
        }
        try {
            String response = http.call(operation, () -> http.client().post().uri(http.uri(API + sid(ctx) + "/Calls.json"))
                    .header("Authorization", apiKeyAuth(ctx)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(String.class));
            String sid = read(response).path("sid").asString("");
            if (!CALL_SID.matcher(sid).matches()) {
                return DialResult.rejected(callId, "TWILIO_NO_CALL_SID", "Resposta sem SID da chamada");
            }
            return DialResult.accepted(callId, sid);
        } catch (Rejected e) {
            return DialResult.rejected(callId, e.code(), "HTTP " + e.status());
        } catch (TransientProviderException e) {
            if (e.notProcessed()) {
                throw e;
            }
            // tempo de leitura esgotado ou 5xx: a Twilio pode ter criado a chamada e não deduplica pedidos
            LOG.atWarn().addKeyValue("provider", METRIC).addKeyValue("callId", callId)
                    .log("Criação de chamada na Twilio não confirmada; não será repetida");
            return DialResult.rejected(callId, "TWILIO_UNCONFIRMED", "Criação não confirmada");
        }
    }

    // ------------------------------------------------------------------ chamada em andamento

    @Override
    public void bridge(BridgeRequest request) {
        ProviderContext ctx = request.context();
        String sid = callSid(request.providerCallId());
        if (!validEndpoint(request.agentEndpoint())) {
            throw new ProviderRejectedException("TWILIO_INVALID_ENDPOINT", "Ramal fora do formato da Twilio");
        }
        update("bridge", ctx, sid, Map.of("Twiml", twiml.connect(request.agentEndpoint(), ctx.callerId(), false, false,
                null)));
    }

    @Override
    public void hangup(HangupRequest request) {
        if (request.providerCallId() == null || request.providerCallId().isBlank()) {
            return;
        }
        String sid = callSid(request.providerCallId());
        Map<String, String> form = request.reason() == HangupReason.ABANDONED
                ? Map.of("Twiml", twiml.abandon())
                // "completed" encerra a chamada também em andamento (canceled só vale em fila/tocando)
                : Map.of("Status", "completed");
        update("hangup", request.context(), sid, form);
    }

    private void update(String operation, ProviderContext ctx, String sid, Map<String, String> values) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        values.forEach(form::add);
        http.rejectedAsException(() -> http.call(operation, () -> http.client().post()
                .uri(http.uri(API + sid(ctx) + "/Calls/" + sid + ".json")).header("Authorization", apiKeyAuth(ctx))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().toBodilessEntity()));
    }

    // ------------------------------------------------------------------ gravações

    @Override
    public RecordingMedia fetchRecording(ProviderContext context, String recordingRef) {
        String sid = recordingSid(recordingRef);
        // dois canais (operador e devedor separados), formato padrão das gravações de chamada da Twilio
        return http.rejectedAsException(() -> http.call("fetch_recording", () -> http.client().get()
                .uri(http.uri(API + sid(context) + "/Recordings/" + sid + ".wav?RequestedChannels=2"))
                .header("Authorization", apiKeyAuth(context))
                .exchange((req, res) -> ProviderHttp.media(res, "Twilio"), false)));
    }

    @Override
    public void deleteRecording(ProviderContext context, String recordingRef) {
        String sid = recordingSid(recordingRef);
        http.rejectedAsException(() -> http.call("delete_recording", () -> http.client().delete()
                .uri(http.uri(API + sid(context) + "/Recordings/" + sid + ".json"))
                .header("Authorization", apiKeyAuth(context)).retrieve().toBodilessEntity()));
    }

    // ------------------------------------------------------------------ verificação e softphone

    /**
     * Leitura da própria conta ({@code GET /2010-04-01/Accounts/{AccountSid}.json}) duas vezes: com a
     * API Key (operações) e com o Auth Token (assinatura dos webhooks). Não cria nada nem liga.
     */
    @Override
    public boolean verifiesCredentials() {
        return true;
    }

    @Override
    public CredentialCheck verifyCredentials(ProviderContext context) {
        try {
            fetchAccount(context, apiKeyAuth(context));
        } catch (Rejected e) {
            return CredentialCheck.failed(e.status() == 401 || e.status() == 403 ? "TWILIO_API_KEY_REJECTED" : e.code());
        } catch (TransientProviderException e) {
            return CredentialCheck.failed("UNREACHABLE");
        }
        try {
            fetchAccount(context, basic(sid(context), context.credentials().get("authToken")));
        } catch (Rejected e) {
            return CredentialCheck.failed(e.status() == 401 || e.status() == 403 ? "TWILIO_AUTH_TOKEN_REJECTED" : e.code());
        } catch (TransientProviderException e) {
            return CredentialCheck.failed("UNREACHABLE");
        }
        return CredentialCheck.passed();
    }

    private void fetchAccount(ProviderContext ctx, String authorization) {
        http.call("verify_credentials", () -> http.client().get().uri(http.uri(API + sid(ctx) + ".json"))
                .header("Authorization", authorization).retrieve().toBodilessEntity());
    }

    @Override
    public Optional<String> softphoneIdentity(String extension) {
        if (extension == null) {
            return Optional.empty();
        }
        var m = CLIENT.matcher(extension.trim());
        return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
    }

    @Override
    public SoftphoneToken issueSoftphoneToken(ProviderContext context, String identity, Duration ttl) {
        if (!CLIENT.matcher("client:" + identity).matches()) {
            throw new ProviderRejectedException("TWILIO_INVALID_IDENTITY", "Identidade do softphone inválida");
        }
        Instant now = Instant.now(clock);
        Duration validity = ttl.compareTo(Duration.ofHours(24)) > 0 ? Duration.ofHours(24) : ttl;
        Instant expiresAt = now.plus(validity);
        Map<String, String> c = context.credentials();
        String token = TwilioSecurity.accessToken(sid(context), c.get("apiKeySid"), c.get("apiKeySecret"), identity,
                c.get("twimlAppSid"), now, expiresAt);
        return new SoftphoneToken(token, identity, expiresAt, Map.of("edge", config.softphoneEdge()));
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

    private static String sid(ProviderContext ctx) {
        String sid = ctx.credentials().get("accountSid");
        if (sid == null || !ACCOUNT_SID.matcher(sid).matches()) {
            throw new ProviderRejectedException("TWILIO_INVALID_ACCOUNT", "Account SID inválido");
        }
        return sid;
    }

    private static String callSid(String value) {
        if (value == null || !CALL_SID.matcher(value).matches()) {
            throw new ProviderRejectedException("TWILIO_MISSING_CALL", "SID da chamada ausente ou inválido");
        }
        return value;
    }

    private static String recordingSid(String value) {
        if (value == null || !RECORDING_SID.matcher(value).matches()) {
            throw new ProviderRejectedException("TWILIO_INVALID_RECORDING", "SID da gravação inválido");
        }
        return value;
    }

    private static boolean validEndpoint(String endpoint) {
        String value = endpoint == null ? "" : endpoint.trim();
        return CLIENT.matcher(value).matches() || SIP.matcher(value).matches() || E164.matcher(value).matches();
    }

    private static String apiKeyAuth(ProviderContext ctx) {
        return basic(ctx.credentials().get("apiKeySid"), ctx.credentials().get("apiKeySecret"));
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(((user == null ? "" : user) + ":"
                + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode read(String body) {
        try {
            return json.mapper().readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (RuntimeException e) {
            throw new TransientProviderException("Resposta ilegível da Twilio", e);
        }
    }

    /** Erro da API: {@code {"code": 21211, "message": …, "status": 400}} → {@code TWILIO_21211}. */
    private String errorCode(Integer status, String body) {
        String code;
        try {
            code = json.mapper().readTree(body == null || body.isBlank() ? "{}" : body).path("code").asString("");
        } catch (RuntimeException e) {
            code = "";
        }
        String stable = code.isBlank() ? "HTTP_" + status : ProviderHttp.code(code);
        LOG.atWarn().addKeyValue("provider", METRIC).addKeyValue("status", status).addKeyValue("errorCode", stable)
                .log("Twilio recusou o pedido");
        return "TWILIO_" + stable;
    }

    private static void require(String value, Pattern pattern, String what) {
        if (value == null || !pattern.matcher(value.trim()).matches()) {
            throw new BusinessRuleException("telephony_account.twilio_credentials_invalid",
                    "Credencial da Twilio inválida: " + what.toLowerCase(Locale.ROOT));
        }
    }
}
