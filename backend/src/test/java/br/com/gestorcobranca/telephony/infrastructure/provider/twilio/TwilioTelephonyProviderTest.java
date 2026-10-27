package br.com.gestorcobranca.telephony.infrastructure.provider.twilio;

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
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.Readiness;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.RecordingMedia;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.TransientProviderException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adaptador da Twilio contra um servidor HTTP local que imita a API REST documentada (Calls,
 * Recordings, Accounts). Nenhuma chamada real.
 */
class TwilioTelephonyProviderTest {

    private static final String ACCOUNT = "AC" + "0123456789abcdef".repeat(2);
    private static final String KEY = "SK" + "fedcba9876543210".repeat(2);
    private static final String CALL = "CA" + "a".repeat(32);
    private static final String DESTINATION = "+5511987654321";

    private FakeHttpApi api;
    private SimpleMeterRegistry registry;
    private TwilioTelephonyProvider provider;

    @BeforeEach
    void start() {
        api = FakeHttpApi.start();
        registry = new SimpleMeterRegistry();
        provider = provider(true);
    }

    @AfterEach
    void stop() {
        api.stop();
    }

    private TwilioTelephonyProvider provider(boolean enabled) {
        TelephonyProperties properties = new TelephonyProperties(null, null, null, null, null, null, null, null, null,
                null, null, null, null, new TelephonyProperties.Twilio(enabled, api.baseUrl() + "/twilio",
                List.of("127.0.0.1"), true, Duration.ofMillis(800), Duration.ofMillis(800), null, null, null, null, null),
                null, null);
        return new TwilioTelephonyProvider(properties, new JsonCodec(JsonMapper.builder().build()),
                new TwilioTwiml(properties), registry, Clock.systemUTC());
    }

    private static ProviderContext context() {
        return new ProviderContext(UUID.randomUUID(), Map.of("accountSid", ACCOUNT, "apiKeySid", KEY,
                "apiKeySecret", "segredodaapikey0123456789", "authToken", "authtoken0123456789abcdef0123456"),
                "+551130000000", "https://gestor.teste/api/v1/webhooks/telephony/rk");
    }

    private String calls() {
        return "/twilio/2010-04-01/Accounts/" + ACCOUNT + "/Calls";
    }

    @Test
    void originateCallsTheOperatorWithInlineTwimlThatDialsTheDebtor() {
        api.respond(calls() + ".json", r -> Response.json(201, "{\"sid\":\"" + CALL + "\",\"status\":\"queued\"}"));
        UUID callId = UUID.randomUUID();

        DialResult result = provider.originate(new OriginateRequest(context(), callId, "client:op_x", DESTINATION, 30,
                true, true));

        assertThat(result.accepted()).isTrue();
        assertThat(result.providerCallId()).isEqualTo(CALL);
        Request request = api.requests(calls()).getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.authorization()).isEqualTo("Basic " + Base64.getEncoder().encodeToString(
                (KEY + ":segredodaapikey0123456789").getBytes(StandardCharsets.UTF_8)));
        assertThat(request.formValue("To")).isEqualTo("client:op_x");
        assertThat(request.formValue("From")).isEqualTo("+551130000000");
        assertThat(request.formValue("StatusCallback"))
                .isEqualTo("https://gestor.teste/api/v1/webhooks/telephony/rk?k=status&ref=" + callId + "&leg=agent");
        String twiml = request.formValue("Twiml");
        assertThat(twiml).contains("<Number statusCallbackEvent=\"ringing answered completed\"")
                .contains(">" + DESTINATION + "</Number>").contains("record=\"record-from-answer-dual\"")
                .contains("k=announce").contains("leg=customer");
        // número do devedor nunca vai em URL de callback
        assertThat(request.formValue("StatusCallback")).doesNotContain("98765");
        assertThat(twiml.replaceAll(">\\+5511987654321<", "")).doesNotContain("98765");
        assertThat(registry.counter("integration.calls", "provider", "twilio", "operation", "originate", "outcome",
                "success").count()).isEqualTo(1);
    }

    @Test
    void dialBatchUsesAsyncAmdRecordingAndOneRequestPerCall() {
        api.respond(calls() + ".json", r -> Response.json(201, "{\"sid\":\"CA" + UUID.randomUUID().toString()
                .replace("-", "") + "\"}"));
        DialRequest withAmd = new DialRequest(UUID.randomUUID(), DESTINATION, 25, true, true, true);
        DialRequest plain = new DialRequest(UUID.randomUUID(), "+5511977771234", 25, false, false, false);

        List<DialResult> results = provider.dialBatch(context(), List.of(withAmd, plain));

        assertThat(results).allSatisfy(r -> assertThat(r.accepted()).isTrue());
        List<Request> sent = api.requests(calls());
        assertThat(sent).hasSize(2);
        Request first = sent.get(0);
        assertThat(first.formValue("MachineDetection")).isEqualTo("Enable");
        assertThat(first.formValue("AsyncAmd")).isEqualTo("true");
        assertThat(first.formValue("AsyncAmdStatusCallback")).contains("k=amd").contains("ref=" + withAmd.callId());
        assertThat(first.formValue("MachineDetectionTimeout")).isEqualTo("8");
        assertThat(first.formValue("Record")).isEqualTo("true");
        assertThat(first.formValue("RecordingChannels")).isEqualTo("dual");
        assertThat(first.form("StatusCallbackEvent")).containsExactly("ringing", "answered", "completed");
        assertThat(first.formValue("StatusCallback")).contains("leg=dialer").contains("amd=1");
        assertThat(first.formValue("Twiml")).contains("<Say").contains("<Pause length=\"28\"/>");
        Request second = sent.get(1);
        assertThat(second.formValue("MachineDetection")).isNull();
        assertThat(second.formValue("Record")).isNull();
        assertThat(second.formValue("StatusCallback")).contains("amd=0");
    }

    @Test
    void errorsAreClassifiedWithoutEverRepeatingAnUnconfirmedCall() {
        UUID callId = UUID.randomUUID();
        api.respond(calls() + ".json", r -> Response.json(400, "{\"code\":21211,\"message\":\"Invalid 'To'\",\"status\":400}"));
        assertThat(provider.originate(new OriginateRequest(context(), callId, "client:op_x", DESTINATION, 30, false, false)))
                .satisfies(r -> {
                    assertThat(r.accepted()).isFalse();
                    assertThat(r.errorCode()).isEqualTo("TWILIO_21211");
                });
        api.respond(calls() + ".json", r -> Response.json(503, "{}"));
        assertThatThrownBy(() -> provider.originate(new OriginateRequest(context(), callId, "client:op_x", DESTINATION,
                30, false, false))).isInstanceOfSatisfying(TransientProviderException.class,
                e -> assertThat(e.notProcessed()).isTrue());
        api.respond(calls() + ".json", r -> Response.json(500, "{}"));
        assertThat(provider.originate(new OriginateRequest(context(), callId, "client:op_x", DESTINATION, 30, false, false))
                .errorCode()).isEqualTo("TWILIO_UNCONFIRMED");
        api.respond(calls() + ".json", r -> {
            sleep(1500);
            return Response.json(201, "{\"sid\":\"" + CALL + "\"}");
        });
        assertThat(provider.originate(new OriginateRequest(context(), callId, "client:op_x", DESTINATION, 30, false, false))
                .errorCode()).isEqualTo("TWILIO_UNCONFIRMED");
        assertThat(provider.originate(new OriginateRequest(context(), callId, "ramal 10", DESTINATION, 30, false, false))
                .errorCode()).isEqualTo("TWILIO_INVALID_ENDPOINT");
    }

    @Test
    void partialBatchFailureRefusesTheRemainingCalls() {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        api.respond(calls() + ".json", r -> n.incrementAndGet() == 1 ? Response.json(201, "{\"sid\":\"" + CALL + "\"}")
                : Response.json(503, "{}"));
        List<DialResult> results = provider.dialBatch(context(), List.of(
                new DialRequest(UUID.randomUUID(), DESTINATION, 25, false, false, false),
                new DialRequest(UUID.randomUUID(), DESTINATION, 25, false, false, false),
                new DialRequest(UUID.randomUUID(), DESTINATION, 25, false, false, false)));
        assertThat(results).extracting(DialResult::accepted).containsExactly(true, false, false);
        assertThat(results.get(2).errorCode()).isEqualTo("TWILIO_BATCH_INTERRUPTED");
    }

    @Test
    void bridgeAndHangupUpdateTheLiveCall() {
        api.respond(calls() + "/" + CALL + ".json", r -> Response.json(200, "{\"sid\":\"" + CALL + "\"}"));

        provider.bridge(new BridgeRequest(context(), UUID.randomUUID(), CALL, "sip:op@pbx.assessoria.com.br"));
        provider.hangup(new HangupRequest(context(), UUID.randomUUID(), CALL, HangupReason.OPERATOR));
        provider.hangup(new HangupRequest(context(), UUID.randomUUID(), CALL, HangupReason.ABANDONED));
        provider.hangup(new HangupRequest(context(), UUID.randomUUID(), null, HangupReason.CANCELLED));

        List<Request> updates = api.requests(calls() + "/" + CALL);
        assertThat(updates).hasSize(3);
        assertThat(updates.get(0).formValue("Twiml")).contains("<Dial timeout=\"20\" callerId=\"+551130000000\">")
                .contains("<Sip>sip:op@pbx.assessoria.com.br</Sip>");
        assertThat(updates.get(1).formValue("Status")).isEqualTo("completed");
        assertThat(updates.get(2).formValue("Twiml")).contains("<Say").contains("atendentes").contains("<Hangup/>");

        api.respond(calls() + "/" + CALL + ".json", r -> Response.json(404, "{\"code\":20404}"));
        assertThatThrownBy(() -> provider.hangup(new HangupRequest(context(), UUID.randomUUID(), CALL,
                HangupReason.OPERATOR))).isInstanceOfSatisfying(ProviderRejectedException.class,
                e -> assertThat(e.code()).isEqualTo("TWILIO_20404"));
        assertThatThrownBy(() -> provider.bridge(new BridgeRequest(context(), UUID.randomUUID(), "x", "client:op")))
                .isInstanceOf(ProviderRejectedException.class);
    }

    @Test
    void recordingIsDownloadedInTwoChannelsAndDeleted() throws Exception {
        String recording = "RE" + "b".repeat(32);
        String path = "/twilio/2010-04-01/Accounts/" + ACCOUNT + "/Recordings/" + recording;
        byte[] wav = "RIFF-falso".getBytes(StandardCharsets.US_ASCII);
        api.respond(path + ".wav", r -> new Response(200, "audio/x-wav", wav));
        api.respond(path + ".json", r -> Response.empty(204));

        RecordingMedia media = provider.fetchRecording(context(), recording);
        try (InputStream in = media.content()) {
            assertThat(in.readAllBytes()).isEqualTo(wav);
        }
        assertThat(media.contentType()).isEqualTo("audio/x-wav");
        assertThat(api.requests(path + ".wav").getFirst().query()).isEqualTo("RequestedChannels=2");
        provider.deleteRecording(context(), recording);
        assertThat(api.requests(path + ".json").getFirst().method()).isEqualTo("DELETE");
        assertThatThrownBy(() -> provider.fetchRecording(context(), "../../Accounts")).isInstanceOf(ProviderRejectedException.class);
    }

    @Test
    void credentialCheckReadsTheAccountWithBothSecrets() {
        String account = "/twilio/2010-04-01/Accounts/" + ACCOUNT + ".json";
        api.respond(account, r -> Response.json(200, "{\"sid\":\"" + ACCOUNT + "\",\"status\":\"active\"}"));
        assertThat(provider.verifyCredentials(context())).isEqualTo(CredentialCheck.passed());
        assertThat(api.requests(account)).hasSize(2).extracting(Request::method).containsOnly("GET");
        assertThat(api.requests(account).get(1).authorization()).isEqualTo("Basic " + Base64.getEncoder()
                .encodeToString((ACCOUNT + ":authtoken0123456789abcdef0123456").getBytes(StandardCharsets.UTF_8)));
        String apiKeyHeader = "Basic " + Base64.getEncoder().encodeToString((KEY + ":segredodaapikey0123456789")
                .getBytes(StandardCharsets.UTF_8));
        api.respond(account, r -> apiKeyHeader.equals(r.authorization()) ? Response.json(200, "{}")
                : Response.json(401, "{\"code\":20003}"));
        assertThat(provider.verifyCredentials(context()).code()).isEqualTo("TWILIO_AUTH_TOKEN_REJECTED");
        assertThat(api.requests(calls())).isEmpty();
    }

    @Test
    void validatesCredentialsEndpointsAndIssuesSoftphoneTokens() {
        provider.validateCredentials(context().credentials());
        assertThatThrownBy(() -> provider.validateCredentials(Map.of("accountSid", "AC123", "apiKeySid", KEY,
                "apiKeySecret", "segredodaapikey0123456789", "authToken", "authtoken0123456789abcdef0123456")))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "telephony_account.twilio_credentials_invalid");
        provider.validateEndpoint("client:op_x");
        provider.validateEndpoint("sip:ramal10@pbx.assessoria.com.br");
        provider.validateEndpoint("+5511999990000");
        assertThatThrownBy(() -> provider.validateEndpoint("client:op x")).isInstanceOf(BusinessRuleException.class);
        assertThat(provider.softphoneIdentity("client:op_x")).hasValue("op_x");
        assertThat(provider.softphoneIdentity("sip:op@x.com")).isEmpty();
        var token = provider.issueSoftphoneToken(context(), "op_x", Duration.ofHours(48));
        assertThat(token.identity()).isEqualTo("op_x");
        assertThat(Duration.between(java.time.Instant.now(), token.expiresAt())).isLessThanOrEqualTo(Duration.ofHours(24));
        assertThat(token.options()).containsEntry("edge", "roaming");
        assertThat(provider.readiness()).isEqualTo(Readiness.READY);
        assertThat(provider(false).readiness()).isEqualTo(Readiness.DISABLED);
    }

    @Test
    void apiAddressOutsideTheAllowlistStopsTheStartup() {
        assertThatThrownBy(() -> new TwilioTelephonyProvider(new TelephonyProperties(null, null, null, null, null, null,
                null, null, null, null, null, null, null, new TelephonyProperties.Twilio(true, "https://api.twilio.com.evil",
                List.of("api.twilio.com"), false, null, null, null, null, null, null, null), null, null),
                new JsonCodec(JsonMapper.builder().build()), null, registry, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
