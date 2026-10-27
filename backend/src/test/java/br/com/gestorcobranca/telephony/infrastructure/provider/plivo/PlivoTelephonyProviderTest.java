package br.com.gestorcobranca.telephony.infrastructure.provider.plivo;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.support.FakeHttpApi;
import br.com.gestorcobranca.support.FakeHttpApi.Request;
import br.com.gestorcobranca.support.FakeHttpApi.Response;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.BridgeRequest;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.CredentialCheck;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.DialRequest;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.DialResult;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.HangupReason;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.HangupRequest;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.OriginateRequest;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.ProviderContext;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.ProviderRejectedException;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.RecordingMedia;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.TransientProviderException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Adaptador da Plivo contra um servidor HTTP local que imita a API documentada. Nenhuma chamada real. */
class PlivoTelephonyProviderTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String AUTH_ID = "MAZDQ1ZWIYZDE4ZGVKN2";
    private static final String TOKEN = "plivoauthtoken0123456789";
    private static final String REQUEST_UUID = "99999999-8888-4777-8666-555555555555";
    private static final String CALL_UUID = "11111111-2222-4333-8444-555555555555";

    private FakeHttpApi api;
    private PlivoTelephonyProvider provider;
    private String recordingHost = "127.0.0.1";

    @BeforeEach
    void start() {
        api = FakeHttpApi.start();
        provider = provider();
    }

    @AfterEach
    void stop() {
        api.stop();
    }

    private PlivoTelephonyProvider provider() {
        TelephonyProperties properties = new TelephonyProperties(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, new TelephonyProperties.Plivo(true, api.baseUrl() + "/plivo",
                List.of("127.0.0.1"), List.of(recordingHost), true, Duration.ofMillis(800), Duration.ofMillis(800), null,
                null, null), null);
        return new PlivoTelephonyProvider(properties, new JsonCodec(MAPPER), new SimpleMeterRegistry());
    }

    private static ProviderContext context() {
        return new ProviderContext(UUID.randomUUID(), Map.of("authId", AUTH_ID, "authToken", TOKEN), "+551130000000",
                "https://gestor.teste/api/v1/webhooks/telephony/rk");
    }

    private String account() {
        return "/plivo/v1/Account/" + AUTH_ID;
    }

    private static JsonNode json(Request r) {
        return MAPPER.readTree(r.body());
    }

    @Test
    void originateCallsTheOperatorAndAsksForInstructionsOnAnswer() {
        api.respond(account() + "/Call/", r -> Response.json(201, "{\"message\":\"call fired\",\"request_uuid\":\""
                + REQUEST_UUID + "\",\"api_id\":\"a\"}"));
        UUID callId = UUID.randomUUID();
        DialResult result = provider.originate(new OriginateRequest(context(), callId, "sip:opx123@phone.plivo.com",
                "+5511987654321", 30, true, true));

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerCallId()).isEqualTo(REQUEST_UUID);
        Request request = api.requests(account() + "/Call/").getFirst();
        assertThat(request.authorization()).isEqualTo("Basic " + Base64.getEncoder()
                .encodeToString((AUTH_ID + ":" + TOKEN).getBytes(StandardCharsets.UTF_8)));
        JsonNode body = json(request);
        assertThat(body.path("from").asString()).isEqualTo("551130000000");
        assertThat(body.path("to").asString()).isEqualTo("sip:opx123@phone.plivo.com");
        assertThat(body.path("answer_url").asString()).isEqualTo("https://gestor.teste/api/v1/webhooks/telephony/rk?k=answer&ref="
                + callId + "&leg=agent");
        assertThat(body.path("hangup_url").asString()).contains("k=hangup");
        // o número do devedor só aparece na resposta do answer_url (corpo), nunca no pedido nem em URL
        assertThat(request.body()).doesNotContain("98765");
    }

    @Test
    void dialBatchUsesAsyncMachineDetection() {
        api.respond(account() + "/Call/", r -> Response.json(201, "{\"request_uuid\":\"" + UUID.randomUUID() + "\"}"));
        DialRequest amd = new DialRequest(UUID.randomUUID(), "+5511987654321", 25, true, true, true);
        DialRequest plain = new DialRequest(UUID.randomUUID(), "+5511977771234", 25, false, false, false);
        assertThat(provider.dialBatch(context(), List.of(amd, plain))).allSatisfy(r -> assertThat(r.accepted()).isTrue());

        List<Request> sent = api.requests(account() + "/Call/");
        JsonNode first = json(sent.get(0));
        assertThat(first.path("to").asString()).isEqualTo("5511987654321");
        assertThat(first.path("machine_detection").asString()).isEqualTo("true");
        assertThat(first.path("machine_detection_time").asInt()).isEqualTo(5000);
        assertThat(first.path("machine_detection_url").asString()).contains("k=amd").contains("ref=" + amd.callId());
        assertThat(first.path("answer_url").asString()).contains("amd=1");
        assertThat(first.path("ring_url").asString()).contains("k=ring");
        assertThat(json(sent.get(1)).has("machine_detection")).isFalse();
        assertThat(json(sent.get(1)).path("answer_url").asString()).contains("amd=0");
    }

    @Test
    void errorsAreClassifiedWithoutRepeatingUnconfirmedCalls() {
        UUID id = UUID.randomUUID();
        api.respond(account() + "/Call/", r -> Response.json(400, "{\"error\":\"invalid destination\"}"));
        assertThat(provider.originate(new OriginateRequest(context(), id, "+5511955554444", "+5511987654321", 30, false,
                false)).errorCode()).isEqualTo("PLIVO_HTTP_400");
        api.respond(account() + "/Call/", r -> Response.json(429, "{}"));
        assertThatThrownBy(() -> provider.originate(new OriginateRequest(context(), id, "+5511955554444", "+5511987654321",
                30, false, false))).isInstanceOfSatisfying(TransientProviderException.class,
                e -> assertThat(e.notProcessed()).isTrue());
        api.respond(account() + "/Call/", r -> Response.json(502, "{}"));
        assertThat(provider.originate(new OriginateRequest(context(), id, "+5511955554444", "+5511987654321", 30, false,
                false)).errorCode()).isEqualTo("PLIVO_UNCONFIRMED");
        assertThat(provider.originate(new OriginateRequest(context(), id, "client:op", "+5511987654321", 30, false,
                false)).errorCode()).isEqualTo("PLIVO_INVALID_ENDPOINT");
    }

    @Test
    void bridgeTransfersTheALegAndHangupNeverTargetsTheWholeAccount() {
        api.respond(account() + "/Call/" + CALL_UUID + "/", r -> r.method().equals("DELETE") ? Response.empty(204)
                : Response.json(202, "{\"message\":\"call transferred\"}"));
        api.respond(account() + "/Request/" + REQUEST_UUID + "/", r -> Response.empty(204));
        UUID callId = UUID.randomUUID();
        UUID operator = UUID.randomUUID();

        provider.bridge(new BridgeRequest(context(), callId, REQUEST_UUID, "sip:opy@phone.plivo.com", CALL_UUID, operator));
        JsonNode transfer = json(api.requests(account() + "/Call/" + CALL_UUID + "/").getFirst());
        assertThat(transfer.path("legs").asString()).isEqualTo("aleg");
        assertThat(transfer.path("aleg_url").asString()).contains("k=bridge").contains("ref=" + callId)
                .contains("op=" + operator).doesNotContain("opy");
        assertThatThrownBy(() -> provider.bridge(new BridgeRequest(context(), callId, REQUEST_UUID, "sip:opy@phone.plivo.com")))
                .isInstanceOfSatisfying(ProviderRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo("PLIVO_MISSING_CALL_UUID"));

        provider.hangup(new HangupRequest(context(), callId, REQUEST_UUID, HangupReason.OPERATOR, CALL_UUID));
        provider.hangup(new HangupRequest(context(), callId, REQUEST_UUID, HangupReason.CANCELLED, null));
        provider.hangup(new HangupRequest(context(), callId, REQUEST_UUID, HangupReason.ABANDONED, CALL_UUID));
        provider.hangup(new HangupRequest(context(), callId, null, HangupReason.CANCELLED, null));
        provider.hangup(new HangupRequest(context(), callId, "", HangupReason.CANCELLED, " "));

        assertThat(api.requests(account() + "/Call/" + CALL_UUID + "/")).extracting(Request::method)
                .containsExactly("POST", "DELETE", "POST");
        assertThat(json(api.requests(account() + "/Call/" + CALL_UUID + "/").get(2)).path("aleg_url").asString())
                .contains("k=abandon");
        assertThat(api.requests(account() + "/Request/" + REQUEST_UUID + "/")).singleElement()
                .extracting(Request::method).isEqualTo("DELETE");
        assertThat(api.requests(account() + "/Call/")).filteredOn(r -> r.path().equals(account() + "/Call/")).isEmpty();
    }

    @Test
    void recordingIsDownloadedOnlyFromAllowedHostsAndDeleted() throws Exception {
        byte[] mp3 = "ID3-falso".getBytes(StandardCharsets.US_ASCII);
        api.respond("/media/rec-0001.mp3", r -> new Response(200, "audio/mpeg", mp3));
        api.respond(account() + "/Recording/rec-0001/", r -> r.method().equals("DELETE") ? Response.empty(204)
                : Response.json(200, "{\"recording_url\":\"" + api.baseUrl() + "/media/rec-0001.mp3\",\"recording_format\":\"mp3\"}"));

        RecordingMedia media = provider.fetchRecording(context(), "rec-0001");
        try (InputStream in = media.content()) {
            assertThat(in.readAllBytes()).isEqualTo(mp3);
        }
        assertThat(media.contentType()).isEqualTo("audio/mpeg");
        assertThat(api.requests("/media/").getFirst().authorization()).startsWith("Basic ");
        provider.deleteRecording(context(), "rec-0001");
        assertThat(api.requests(account() + "/Recording/rec-0001/")).extracting(Request::method).containsExactly("GET", "DELETE");

        api.respond(account() + "/Recording/rec-0002/", r -> Response.json(200,
                "{\"recording_url\":\"https://intranet.local/rec.mp3\"}"));
        assertThatThrownBy(() -> provider.fetchRecording(context(), "rec-0002"))
                .isInstanceOfSatisfying(ProviderRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo("PLIVO_RECORDING_HOST_NOT_ALLOWED"));
        assertThatThrownBy(() -> provider.fetchRecording(context(), "../x")).isInstanceOf(ProviderRejectedException.class);
    }

    @Test
    void credentialCheckAndValidation() {
        api.respond(account() + "/", r -> r.path().equals(account() + "/") ? Response.json(200, "{\"auth_id\":\"" + AUTH_ID
                + "\"}") : Response.empty(404));
        assertThat(provider.verifyCredentials(context())).isEqualTo(CredentialCheck.passed());
        api.respond(account() + "/", r -> Response.json(401, "{\"error\":\"not authorized\"}"));
        assertThat(provider.verifyCredentials(context()).code()).isEqualTo("PLIVO_AUTH_REJECTED");
        assertThat(api.requests(account() + "/Call/")).isEmpty();

        provider.validateCredentials(Map.of("authId", AUTH_ID, "authToken", TOKEN));
        assertThatThrownBy(() -> provider.validateCredentials(Map.of("authId", "XX", "authToken", TOKEN)))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "telephony_account.plivo_credentials_invalid");
        provider.validateEndpoint("sip:opx123@phone.plivo.com");
        provider.validateEndpoint("+5511999990000");
        assertThatThrownBy(() -> provider.validateEndpoint("client:op")).isInstanceOf(BusinessRuleException.class);
        assertThat(provider.softphoneIdentity("sip:opx123@phone.plivo.com")).isEmpty();
    }
}
