package br.com.gestorcobranca.telephony;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.support.FakeHttpApi;
import br.com.gestorcobranca.support.FakeHttpApi.Request;
import br.com.gestorcobranca.support.FakeHttpApi.Response;
import br.com.gestorcobranca.telephony.application.port.in.AgentUseCases.ConfigureEndpointCommand;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.CallView;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.ClickToCallCommand;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CampaignSettings;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CampaignView;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CreateCampaignCommand;
import br.com.gestorcobranca.telephony.application.port.in.SoftphoneUseCases;
import br.com.gestorcobranca.telephony.application.port.in.SoftphoneUseCases.SoftphoneSession;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyAccountUseCases.AccountView;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyAccountUseCases.ConnectionTestView;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyAccountUseCases.CreateAccountCommand;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyAccountUseCases.ProviderView;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyRoutingUseCases;
import br.com.gestorcobranca.telephony.application.port.in.TelephonyRoutingUseCases.SaveRouteCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Twilio e Plivo dublados ao mesmo tempo por um servidor HTTP local (nenhuma chamada real), com os
 * webhooks entregues pela porta HTTP real da aplicação e assinados como os provedores assinam
 * ({@code X-Twilio-Signature}, {@code X-Plivo-Signature-V3} com nonce) sobre o endereço público
 * configurado. Clique para ligar, chamada recebida com TwiML na resposta, discador com AMD e espera,
 * contingência Twilio → Plivo com uma única tentativa no compliance, gravação copiada e removida no
 * provedor, teste de conexão, catálogo, console e softphone.
 */
class TelephonyCpaasIT extends TelephonyTestSupport {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String PUBLIC = "https://gestor.teste";
    private static final String TW_ACCOUNT = "AC" + "0123456789abcdef".repeat(2);
    private static final String TW_KEY = "SK" + "fedcba9876543210".repeat(2);
    private static final String TW_TOKEN = "twilioauthtoken0123456789abcdef0";
    private static final String PL_AUTH_ID = "MAZDQ1ZWIYZDE4ZGVKN2";
    private static final String PL_TOKEN = "plivoauthtoken0123456789";
    private static final Pattern URL_ATTR = Pattern.compile("statusCallback=\"([^\"]+)\"");

    private final FakeHttpApi api = FakeHttpApi.shared();
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicInteger sequence = new AtomicInteger();

    @LocalServerPort
    int port;
    @Autowired
    TelephonyRoutingUseCases routing;
    @Autowired
    SoftphoneUseCases softphone;

    AccountView twilio;
    AccountView plivo;

    @BeforeEach
    void setUp() {
        api.reset("/twilio");
        api.reset("/plivo");
        api.reset("/media");
        api.respond("/twilio/2010-04-01/Accounts/" + TW_ACCOUNT + "/Calls.json", r -> Response.json(201,
                "{\"sid\":\"" + callSid() + "\",\"status\":\"queued\"}"));
        api.respond("/plivo/v1/Account/" + PL_AUTH_ID + "/Call/", r -> Response.json(201,
                "{\"message\":\"call fired\",\"request_uuid\":\"" + UUID.randomUUID() + "\",\"api_id\":\"x\"}"));
        provision("cpaas", false);
        twilio = asAdmin(() -> accounts.create(new CreateAccountCommand("TWILIO", null, "Twilio", "+551140000001", true,
                true, Map.of("accountSid", TW_ACCOUNT, "apiKeySid", TW_KEY, "apiKeySecret", "segredodaapikey0123456789",
                "authToken", TW_TOKEN, "twimlAppSid", ""), new BigDecimal("0.1600"))));
        plivo = asAdmin(() -> accounts.create(new CreateAccountCommand("PLIVO", null, "Plivo", "+551140000002", true, true,
                Map.of("authId", PL_AUTH_ID, "authToken", PL_TOKEN), new BigDecimal("0.1000"))));
        route("CREDITOR", creditorA, twilio.id(), plivo.id());
        route("CREDITOR", creditorB, plivo.id(), null);
    }

    private String callSid() {
        return "CA" + String.format("%032x", sequence.incrementAndGet());
    }

    private void route(String scope, UUID scopeId, UUID primary, UUID fallback) {
        asManager(() -> routing.save(new SaveRouteCommand(scope, scopeId, primary, fallback)));
    }

    private void endpoint(UUID operator, AccountView account, String extension) {
        asManager(() -> agents.configureEndpoint(operator, account.id(), new ConfigureEndpointCommand(extension, true)));
    }

    private String routingKey(AccountView account) {
        return account.webhookPath().substring(account.webhookPath().lastIndexOf('/') + 1);
    }

    // ------------------------------------------------------------------ webhooks HTTP assinados

    private HttpResponse<String> twilioWebhook(AccountView account, String query, Map<String, String> form, String token)
            throws Exception {
        String path = "/api/v1/webhooks/telephony/" + routingKey(account) + (query.isEmpty() ? "" : "?" + query);
        StringBuilder data = new StringBuilder(PUBLIC + path);
        new TreeMap<>(form).forEach((k, v) -> data.append(k).append(v));
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        String signature = Base64.getEncoder().encodeToString(mac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));
        return post(path, form, Map.of("X-Twilio-Signature", signature));
    }

    private HttpResponse<String> plivoWebhook(AccountView account, String query, Map<String, String> form, String token)
            throws Exception {
        String path = "/api/v1/webhooks/telephony/" + routingKey(account) + (query.isEmpty() ? "" : "?" + query);
        Map<String, List<String>> sortedQuery = new TreeMap<>();
        for (String pair : query.isEmpty() ? new String[0] : query.split("&")) {
            String[] kv = pair.split("=", 2);
            sortedQuery.computeIfAbsent(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), k -> new ArrayList<>())
                    .add(URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        StringJoiner q = new StringJoiner("&");
        sortedQuery.forEach((k, vs) -> vs.stream().sorted().forEach(v -> q.add(k + "=" + v)));
        StringBuilder base = new StringBuilder(PUBLIC + path.split("\\?")[0]);
        if (q.length() > 0 || !form.isEmpty()) {
            base.append('?').append(q);
        }
        if (q.length() > 0 && !form.isEmpty()) {
            base.append('.');
        }
        new TreeMap<>(form).forEach((k, v) -> base.append(k).append(v));
        String nonce = UUID.randomUUID().toString().replace("-", "");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getEncoder().encodeToString(mac.doFinal((base + "." + nonce)
                .getBytes(StandardCharsets.UTF_8)));
        return post(path, form, Map.of("X-Plivo-Signature-V3", signature, "X-Plivo-Signature-V3-Nonce", nonce));
    }

    private HttpResponse<String> post(String path, Map<String, String> form, Map<String, String> headers) throws Exception {
        String body = form.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, String> form(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String query(String url) {
        return url.substring(url.indexOf('?') + 1);
    }

    private static String param(String url, String name) {
        for (String pair : query(url).split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private String providerCallId(UUID callId) {
        return string("select provider_call_id from telephony.phone_call where id = ?", callId);
    }

    private CallView clickToCall(UUID operator, UUID caseId) {
        return asOperator(operator, () -> calls.clickToCall(new ClickToCallCommand(caseId, null), null));
    }

    // ------------------------------------------------------------------ cenários

    @Test
    void twilioClickToCallRecordsAndEndsThroughSignedWebhooks() throws Exception {
        endpoint(operatorX, twilio, "client:op_x");
        CaseView kase = loadCase(creditorA, portfolioA, debtor("529.982.247-25"), "900.00");
        asOperator(operatorX, () -> agents.login(null));

        CallView queued = clickToCall(operatorX, kase.id());
        assertThat(queued.provider()).isEqualTo("TWILIO");
        drainTelephony();
        Request create = api.requests("/twilio/2010-04-01/Accounts/" + TW_ACCOUNT + "/Calls.json").getFirst();
        assertThat(create.formValue("To")).isEqualTo("client:op_x");
        assertThat(create.formValue("From")).isEqualTo("+551140000001");
        String parent = providerCallId(queued.id());
        assertThat(call(queued.id()).status()).isEqualTo("DIALING");
        Matcher m = URL_ATTR.matcher(create.formValue("Twiml"));
        assertThat(m.find()).isTrue();
        String customerStatus = m.group(1).replace("&amp;", "&");
        assertThat(customerStatus).startsWith(PUBLIC + "/api/v1/webhooks/telephony/" + routingKey(twilio))
                .contains("leg=customer").doesNotContain("98765");
        String child = "CA" + "c".repeat(32);

        Map<String, String> ringing = form("CallSid", child, "ParentCallSid", parent, "CallStatus", "ringing",
                "AccountSid", TW_ACCOUNT, "SequenceNumber", "1");
        assertThat(twilioWebhook(twilio, query(customerStatus), ringing, TW_TOKEN).statusCode()).isEqualTo(200);
        assertThat(call(queued.id()).status()).isEqualTo("RINGING");
        // assinatura com outro token ou URL diferente: 401 e WEBHOOK_REJECTED
        assertThat(twilioWebhook(twilio, query(customerStatus), form("CallSid", child, "ParentCallSid", parent,
                "CallStatus", "completed"), "outrotoken0123456789abcdef012345").statusCode()).isEqualTo(401);
        assertThat(count("select count(*) from shared.access_log where event_type = 'WEBHOOK_REJECTED'"
                + " and details ->> 'provider' = 'TWILIO'")).isGreaterThanOrEqualTo(1);
        Map<String, String> answered = form("CallSid", child, "ParentCallSid", parent, "CallStatus", "in-progress",
                "SequenceNumber", "2");
        assertThat(twilioWebhook(twilio, query(customerStatus), answered, TW_TOKEN).statusCode()).isEqualTo(200);
        // reentrega idêntica: sem efeito
        assertThat(twilioWebhook(twilio, query(customerStatus), answered, TW_TOKEN).statusCode()).isEqualTo(200);
        CallView talking = call(queued.id());
        assertThat(talking.status()).isEqualTo("ANSWERED");
        assertThat(talking.connectedAt()).isNotNull();

        // aviso de gravação ao devedor (url do <Number>)
        HttpResponse<String> announce = twilioWebhook(twilio, "k=announce&ref=" + queued.id(), form("CallSid", child),
                TW_TOKEN);
        assertThat(announce.statusCode()).isEqualTo(200);
        assertThat(announce.headers().firstValue("Content-Type").orElse("")).contains("xml");
        assertThat(announce.body()).contains("<Say").contains("pode ser gravada");

        String recording = "RE" + "d".repeat(32);
        String recordingPath = "/twilio/2010-04-01/Accounts/" + TW_ACCOUNT + "/Recordings/" + recording;
        api.respond(recordingPath + ".wav", r -> new Response(200, "audio/x-wav", "RIFF-teste".getBytes()));
        api.respond(recordingPath + ".json", r -> Response.empty(204));
        assertThat(twilioWebhook(twilio, "k=recording&ref=" + queued.id(), form("CallSid", parent, "RecordingSid",
                recording, "RecordingStatus", "completed", "RecordingDuration", "35"), TW_TOKEN).statusCode()).isEqualTo(200);
        assertThat(twilioWebhook(twilio, query(customerStatus), form("CallSid", child, "ParentCallSid", parent,
                "CallStatus", "completed", "CallDuration", "35"), TW_TOKEN).statusCode()).isEqualTo(200);
        drainTelephony();

        CallView ended = call(queued.id());
        assertThat(ended.status()).isEqualTo("COMPLETED");
        assertThat(ended.result()).isEqualTo("ANSWERED");
        assertThat(string("select status from telephony.call_recording where call_id = ?", queued.id())).isEqualTo("STORED");
        assertThat(api.requests(recordingPath + ".json")).extracting(Request::method).containsExactly("DELETE");
        assertThat(count("select count(*) from shared.business_event where event_type = 'call.dialed' "
                + "and attributes ->> 'provider' = 'TWILIO'")).isEqualTo(1);
    }

    @Test
    void twilioInboundCallGetsTheOperatorOrTheAbandonMessageInTheTwimlResponse() throws Exception {
        endpoint(operatorX, twilio, "client:op_x");
        asOperator(operatorX, () -> agents.login(null));

        HttpResponse<String> first = twilioWebhook(twilio, "k=inbound", form("CallSid", "CA" + "e".repeat(32),
                "From", "+5511987654321", "To", "+551140000001", "Direction", "inbound", "CallStatus", "ringing"), TW_TOKEN);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("<Dial").contains("<Client>op_x</Client>")
                .contains("record=\"record-from-answer-dual\"").contains("pode ser gravada");
        assertThat(count("select count(*) from telephony.phone_call where direction = 'INBOUND' and operator_id = ?",
                operatorX)).isEqualTo(1);

        HttpResponse<String> second = twilioWebhook(twilio, "k=inbound", form("CallSid", "CA" + "f".repeat(32),
                "From", "+5511977771234", "Direction", "inbound", "CallStatus", "ringing"), TW_TOKEN);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).contains("atendentes").contains("<Hangup/>").doesNotContain("<Dial");
        assertThat(count("select count(*) from telephony.phone_call where direction = 'INBOUND' and status = 'NO_ANSWER'"))
                .isEqualTo(1);
        assertThat(api.requests("/twilio")).isEmpty();
    }

    @Test
    void plivoDialerHoldsDuringAmdThenConnectsOrHangsUpOnMachines() throws Exception {
        UUID queue = queueA("CPAAS");
        CaseView first = loadCase(creditorA, portfolioA, debtor("529.982.247-25"), "900.00");
        CampaignView campaign = asManager(() -> dialer.create(new CreateCampaignCommand("PLV", null, queue,
                new CampaignSettings("Campanha Plivo", null, false, "PROGRESSIVE", null, null, 3, 30, 20, null, null))));
        route("CAMPAIGN", campaign.id(), plivo.id(), null);
        endpoint(operatorX, plivo, "sip:opx123@phone.plivo.com");
        asSupervisor(() -> dialer.activate(campaign.id()));
        asOperator(operatorX, () -> agents.login(campaign.id()));
        drainTelephony();

        Request dial = api.requests("/plivo/v1/Account/" + PL_AUTH_ID + "/Call/").getFirst();
        JsonNode body = MAPPER.readTree(dial.body());
        assertThat(body.path("machine_detection").asString()).isEqualTo("true");
        assertThat(body.path("to").asString()).isEqualTo("5511987654321");
        String answerUrl = body.path("answer_url").asString();
        UUID callId = UUID.fromString(param(answerUrl, "ref"));
        assertThat(call(callId).caseId()).isEqualTo(first.id());
        String requestUuid = providerCallId(callId);
        String callUuid = UUID.randomUUID().toString();

        assertThat(plivoWebhook(plivo, query(body.path("ring_url").asString()), form("CallUUID", callUuid, "RequestUUID",
                requestUuid, "Event", "Ring"), PL_TOKEN).statusCode()).isEqualTo(200);
        assertThat(string("select provider_leg_id from telephony.phone_call where id = ?", callId)).isEqualTo(callUuid);
        HttpResponse<String> hold = plivoWebhook(plivo, query(answerUrl), form("CallUUID", callUuid, "RequestUUID",
                requestUuid, "CallStatus", "in-progress"), PL_TOKEN);
        assertThat(hold.body()).contains("<Wait").contains("k=amdwait").contains("<Record recordSession=\"true\"");
        assertThat(call(callId).status()).isEqualTo("RINGING");
        // assinatura com outro token: 401
        assertThat(plivoWebhook(plivo, query(answerUrl), form("CallUUID", callUuid), "outro0123456789abcdef").statusCode())
                .isEqualTo(401);

        HttpResponse<String> connect = plivoWebhook(plivo, "k=amdwait&ref=" + callId, form("CallUUID", callUuid,
                "RequestUUID", requestUuid), PL_TOKEN);
        assertThat(connect.body()).contains("<User>sip:opx123@phone.plivo.com</User>").doesNotContain("<Record");
        CallView connected = call(callId);
        assertThat(connected.operatorId()).isEqualTo(operatorX);
        assertThat(connected.amdResult()).isEqualTo("UNKNOWN");
        assertThat(plivoWebhook(plivo, query(body.path("hangup_url").asString()), form("CallUUID", callUuid, "RequestUUID",
                requestUuid, "AnswerTime", "2026-10-26 12:00:00", "HangupSource", "Callee"), PL_TOKEN).statusCode())
                .isEqualTo(200);
        assertThat(call(callId).status()).isEqualTo("COMPLETED");
        assertThat(call(callId).result()).isEqualTo("ANSWERED");

        // segundo caso: secretária eletrônica → encerrado pela API (DELETE /Call/{CallUUID}/)
        asOperator(operatorX, () -> calls.tabulate(callId,
                new br.com.gestorcobranca.telephony.application.port.in.CallUseCases.TabulateCommand("CPC_NEGOTIATING",
                        null, null, null, null)));
        CaseView second = loadCase(creditorA, portfolioA, debtor("111.444.777-35", "(11) 97777-1234"), "300.00");
        asSupervisor(() -> dialer.reload(campaign.id()));
        drainTelephony();
        List<Request> dials = api.requests("/plivo/v1/Account/" + PL_AUTH_ID + "/Call/").stream()
                .filter(r -> r.path().endsWith("/Call/")).toList();
        assertThat(dials).hasSize(2);
        JsonNode secondBody = MAPPER.readTree(dials.get(1).body());
        UUID secondCall = UUID.fromString(param(secondBody.path("answer_url").asString(), "ref"));
        assertThat(call(secondCall).caseId()).isEqualTo(second.id());
        String secondLeg = UUID.randomUUID().toString();
        api.respond("/plivo/v1/Account/" + PL_AUTH_ID + "/Call/" + secondLeg + "/", r -> Response.empty(204));
        assertThat(plivoWebhook(plivo, query(secondBody.path("machine_detection_url").asString()), form("CallUUID", secondLeg,
                "RequestUUID", providerCallId(secondCall), "Machine", "true", "Event", "MachineDetection"), PL_TOKEN)
                .statusCode()).isEqualTo(200);
        assertThat(call(secondCall).result()).isEqualTo("VOICEMAIL");
        assertThat(api.requests("/plivo/v1/Account/" + PL_AUTH_ID + "/Call/" + secondLeg + "/"))
                .extracting(Request::method).containsExactly("DELETE");
    }

    @Test
    void twilioOutageFallsBackToPlivoOnceAndPlivoDialsTheDebtorFromTheAnswerResponse() throws Exception {
        endpoint(operatorX, twilio, "client:op_x");
        endpoint(operatorX, plivo, "sip:opx123@phone.plivo.com");
        CaseView kase = loadCase(creditorA, portfolioA, debtor("529.982.247-25"), "900.00");
        asOperator(operatorX, () -> agents.login(null));
        api.respond("/twilio/2010-04-01/Accounts/" + TW_ACCOUNT + "/Calls.json", r -> Response.json(503, "{}"));

        CallView queued = clickToCall(operatorX, kase.id());
        assertThat(queued.provider()).isEqualTo("TWILIO");
        drainTelephony();

        CallView dialed = call(queued.id());
        assertThat(dialed.provider()).isEqualTo("PLIVO");
        assertThat(dialed.fallbackFromAccountId()).isEqualTo(twilio.id());
        assertThat(dialed.status()).isEqualTo("DIALING");
        assertThat(count("select count(*) from compliance.contact_attempt where reference_id = ?", queued.id())).isEqualTo(1);
        Request originate = api.requests("/plivo/v1/Account/" + PL_AUTH_ID + "/Call/").getFirst();
        JsonNode body = MAPPER.readTree(originate.body());
        assertThat(body.path("to").asString()).isEqualTo("sip:opx123@phone.plivo.com");
        assertThat(originate.body()).doesNotContain("98765");

        String callUuid = UUID.randomUUID().toString();
        HttpResponse<String> answer = plivoWebhook(plivo, query(body.path("answer_url").asString()), form("CallUUID",
                callUuid, "RequestUUID", providerCallId(queued.id()), "CallStatus", "in-progress"), PL_TOKEN);
        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.body()).contains("<Number>5511987654321</Number>").contains("callerId=\"551140000002\"")
                .contains("startOnDialAnswer=\"true\"").contains("confirmSound=");
        assertThat(call(queued.id()).status()).isEqualTo("RINGING");
        assertThat(plivoWebhook(plivo, "k=dial&ref=" + queued.id(), form("DialAction", "answer", "DialBLegUUID", "b-1",
                "DialALegUUID", callUuid), PL_TOKEN).statusCode()).isEqualTo(200);
        assertThat(call(queued.id()).status()).isEqualTo("ANSWERED");
        assertThat(plivoWebhook(plivo, "k=dial&ref=" + queued.id(), form("DialAction", "hangup", "DialBLegUUID", "b-1",
                "DialALegUUID", callUuid, "DialBLegHangupSource", "Callee"), PL_TOKEN).statusCode()).isEqualTo(200);
        CallView ended = call(queued.id());
        assertThat(ended.status()).isEqualTo("COMPLETED");
        assertThat(ended.hangupSide()).isEqualTo("DEBTOR");
        assertThat(count("select count(*) from telephony.phone_call where case_id = ?", kase.id())).isEqualTo(1);
    }

    @Test
    void catalogConsoleConnectionTestAndSoftphone() throws Exception {
        List<ProviderView> providers = asManager(accounts::providers);
        assertThat(providers).extracting(ProviderView::provider).contains("TWILIO", "PLIVO");
        assertThat(providers).filteredOn(p -> p.provider().equals("TWILIO")).singleElement().satisfies(p -> {
            assertThat(p.readiness()).isEqualTo("READY");
            assertThat(p.dialable()).isTrue();
            assertThat(p.verifiable()).isTrue();
            assertThat(p.optionalCredentials()).containsExactly("twimlAppSid");
        });
        assertThat(twilio.active()).isTrue();
        assertThat(twilio.credentialKeys()).doesNotContain("twimlAppSid");
        assertThat(twilio.webhookUrl()).isEqualTo(PUBLIC + twilio.webhookPath());
        assertThat(twilio.consoleEndpoints()).anySatisfy(e -> assertThat(e.url()).isEqualTo(twilio.webhookUrl() + "?k=inbound"));
        assertThat(plivo.consoleEndpoints()).anySatisfy(e -> assertThat(e.url()).endsWith("?k=hangup&leg=inbound"));

        api.respond("/twilio/2010-04-01/Accounts/" + TW_ACCOUNT + ".json", r -> Response.json(200, "{\"status\":\"active\"}"));
        ConnectionTestView ok = asAdmin(() -> accounts.testConnection(twilio.id()));
        assertThat(ok.ok()).isTrue();
        api.respond("/plivo/v1/Account/" + PL_AUTH_ID + "/", r -> Response.json(401, "{\"error\":\"unauthorized\"}"));
        ConnectionTestView refused = asAdmin(() -> accounts.testConnection(plivo.id()));
        assertThat(refused.ok()).isFalse();
        assertThat(refused.code()).isEqualTo("PLIVO_AUTH_REJECTED");
        assertThat(count("select count(*) from shared.audit_event where action = 'telephony_account.connection_tested'"))
                .isEqualTo(2);
        assertThat(api.requests("/twilio/2010-04-01/Accounts/" + TW_ACCOUNT + "/Calls")).isEmpty();

        assertThatThrownBy(() -> endpoint(operatorX, twilio, "ramal10")).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "agent_endpoint.twilio_invalid");
        assertThatThrownBy(() -> asAdmin(() -> accounts.create(new CreateAccountCommand("TWILIO", null, "Errada",
                "+551140000009", false, false, Map.of("accountSid", "AC1", "apiKeySid", TW_KEY, "apiKeySecret",
                        "segredodaapikey0123456789", "authToken", TW_TOKEN))))).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "telephony_account.twilio_credentials_invalid");

        endpoint(operatorX, twilio, "client:op_x");
        List<SoftphoneSession> sessions = asOperator(operatorX, softphone::sessions);
        assertThat(sessions).singleElement().satisfies(s -> {
            assertThat(s.provider()).isEqualTo("TWILIO");
            assertThat(s.identity()).isEqualTo("op_x");
            assertThat(s.token().split("\\.")).hasSize(3);
            JsonNode payload = MAPPER.readTree(Base64.getUrlDecoder().decode(s.token().split("\\.")[1]));
            assertThat(payload.path("grants").path("identity").asString()).isEqualTo("op_x");
            assertThat(payload.path("sub").asString()).isEqualTo(TW_ACCOUNT);
        });
        assertThat(asOperator(operatorY, softphone::sessions)).isEmpty();

        HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/")).GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(page.headers().firstValue("Content-Security-Policy").orElseThrow())
                .contains("connect-src 'self' https://eventgw.twilio.com https://sdk.twilio.com wss://voice-js.roaming.twilio.com")
                .contains("script-src 'self';");
        assertThat(page.headers().firstValue("Permissions-Policy").orElseThrow()).contains("microphone=(self)")
                .contains("camera=()");
    }
}
