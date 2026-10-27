package br.com.gestorcobranca.telephony.infrastructure.provider.twilio;

import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.out.CallInstruction;
import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent;
import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent.Type;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyWebhookAdapter.InstructionRequest.Kind;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyWebhookAdapter.WebhookRequest;
import br.com.gestorcobranca.telephony.infrastructure.provider.WebhookForms;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TwilioWebhookAdapterTest {

    private static final String TOKEN = "authtoken0123456789abcdef0123456";
    private static final String BASE = "https://gestor.teste/api/v1/webhooks/telephony/rk";
    private static final String PARENT = "CA" + "1".repeat(32);
    private static final String CHILD = "CA" + "2".repeat(32);
    private static final Instant NOW = Instant.parse("2026-10-26T15:00:00Z");

    private final TelephonyProperties properties = new TelephonyProperties(null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);
    private final TwilioWebhookAdapter adapter = new TwilioWebhookAdapter(new TwilioTwiml(properties));
    private final UUID ref = UUID.randomUUID();

    private WebhookRequest request(Map<String, String> query, Map<String, String> form, String token) {
        String url = WebhookForms.callback(BASE, query);
        byte[] body = form.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"))
                .getBytes(StandardCharsets.UTF_8);
        Map<String, List<String>> params = new LinkedHashMap<>();
        form.forEach((k, v) -> params.put(k, List.of(v)));
        String signature = TwilioSecurity.signature(token, url, params);
        return new WebhookRequest(url, query, Map.of(TwilioWebhookAdapter.HEADER, signature), body,
                "application/x-www-form-urlencoded", NOW);
    }

    private static Map<String, String> query(String... kv) {
        Map<String, String> q = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            q.put(kv[i], kv[i + 1]);
        }
        return q;
    }

    private static Map<String, String> form(String... kv) {
        return query(kv);
    }

    @Test
    void signatureCoversTheUrlQueryAndEveryParameter() {
        WebhookRequest valid = request(query("k", "status", "ref", ref.toString(), "leg", "customer"),
                form("CallSid", CHILD, "ParentCallSid", PARENT, "CallStatus", "ringing"), TOKEN);
        Map<String, String> credentials = Map.of("authToken", TOKEN);
        assertThat(adapter.validSignature(credentials, valid, NOW)).isTrue();
        assertThat(adapter.validSignature(Map.of("authToken", "outrotoken0123456789abcdef012345"), valid, NOW)).isFalse();
        // URL diferente (outra referência) com a mesma assinatura
        WebhookRequest otherUrl = new WebhookRequest(valid.url().replace(ref.toString(), UUID.randomUUID().toString()),
                valid.query(), valid.headers(), valid.body(), valid.contentType(), NOW);
        assertThat(adapter.validSignature(credentials, otherUrl, NOW)).isFalse();
        // corpo adulterado
        WebhookRequest tampered = new WebhookRequest(valid.url(), valid.query(), valid.headers(),
                new String(valid.body(), StandardCharsets.UTF_8).replace("ringing", "completed")
                        .getBytes(StandardCharsets.UTF_8), valid.contentType(), NOW);
        assertThat(adapter.validSignature(credentials, tampered, NOW)).isFalse();
        WebhookRequest unsigned = new WebhookRequest(valid.url(), valid.query(), Map.of(), valid.body(), null, NOW);
        assertThat(adapter.validSignature(credentials, unsigned, NOW)).isFalse();
    }

    @Test
    void customerLegStatusesMapToTheParentCall() {
        List<ProviderCallEvent> ringing = adapter.parse(request(query("k", "status", "ref", ref.toString(), "leg", "customer"),
                form("CallSid", CHILD, "ParentCallSid", PARENT, "CallStatus", "ringing",
                        "Timestamp", "Mon, 26 Oct 2026 14:59:58 +0000"), TOKEN));
        assertThat(ringing).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(Type.RINGING);
            assertThat(e.providerCallId()).isEqualTo(PARENT);
            assertThat(e.callReference()).isEqualTo(ref);
            assertThat(e.eventId()).isEqualTo(CHILD + ":ringing");
            assertThat(e.occurredAt()).isEqualTo(Instant.parse("2026-10-26T14:59:58Z"));
        });
        assertThat(adapter.parse(request(query("k", "status", "ref", ref.toString(), "leg", "customer"),
                form("CallSid", CHILD, "ParentCallSid", PARENT, "CallStatus", "in-progress"), TOKEN)))
                .singleElement().satisfies(e -> {
                    assertThat(e.type()).isEqualTo(Type.ANSWERED);
                    assertThat(e.occurredAt()).isEqualTo(NOW);
                });
        assertThat(adapter.parse(request(query("k", "status", "ref", ref.toString(), "leg", "customer"),
                form("CallSid", CHILD, "ParentCallSid", PARENT, "CallStatus", "failed", "SipResponseCode", "404"), TOKEN)))
                .singleElement().satisfies(e -> {
                    assertThat(e.type()).isEqualTo(Type.FAILED);
                    assertThat(e.cause()).isEqualTo("TWILIO_SIP_404");
                });
        Map<String, Type> statuses = Map.of("completed", Type.COMPLETED, "busy", Type.BUSY, "no-answer", Type.NO_ANSWER,
                "canceled", Type.CANCELLED);
        statuses.forEach((status, type) -> assertThat(adapter.parse(request(query("k", "status", "ref", ref.toString(),
                "leg", "customer"), form("CallSid", CHILD, "ParentCallSid", PARENT, "CallStatus", status), TOKEN)))
                .singleElement().extracting(ProviderCallEvent::type).isEqualTo(type));
        assertThat(adapter.parse(request(query("k", "status", "ref", ref.toString(), "leg", "customer"),
                form("CallSid", CHILD, "ParentCallSid", PARENT, "CallStatus", "initiated"), TOKEN))).isEmpty();
    }

    @Test
    void agentLegOnlyReportsTheEndAndDialerWithAmdWaitsForTheVerdict() {
        assertThat(adapter.parse(request(query("k", "status", "ref", ref.toString(), "leg", "agent"),
                form("CallSid", PARENT, "CallStatus", "in-progress"), TOKEN))).isEmpty();
        assertThat(adapter.parse(request(query("k", "status", "ref", ref.toString(), "leg", "agent"),
                form("CallSid", PARENT, "CallStatus", "no-answer"), TOKEN))).singleElement().satisfies(e -> {
                    assertThat(e.type()).isEqualTo(Type.NO_ANSWER);
                    assertThat(e.providerCallId()).isEqualTo(PARENT);
                    assertThat(e.cause()).isEqualTo("AGENT_NO_ANSWER");
                });
        assertThat(adapter.parse(request(query("k", "status", "ref", ref.toString(), "leg", "dialer", "amd", "1"),
                form("CallSid", PARENT, "CallStatus", "in-progress"), TOKEN))).isEmpty();
        assertThat(adapter.parse(request(query("k", "status", "ref", ref.toString(), "leg", "dialer", "amd", "0"),
                form("CallSid", PARENT, "CallStatus", "in-progress"), TOKEN))).singleElement()
                .extracting(ProviderCallEvent::type).isEqualTo(Type.ANSWERED);
    }

    @Test
    void asyncAmdVerdictBecomesTheAnswer() {
        Map<String, String> verdicts = Map.of("human", "HUMAN", "machine_start", "MACHINE", "fax", "MACHINE",
                "machine_end_beep", "MACHINE", "unknown", "UNKNOWN");
        verdicts.forEach((answeredBy, amd) -> assertThat(adapter.parse(request(query("k", "amd", "ref", ref.toString()),
                form("CallSid", PARENT, "AnsweredBy", answeredBy, "MachineDetectionDuration", "2500"), TOKEN)))
                .singleElement().satisfies(e -> {
                    assertThat(e.type()).isEqualTo(Type.ANSWERED);
                    assertThat(e.amd()).isEqualTo(amd);
                    assertThat(e.eventId()).isEqualTo(PARENT + ":amd");
                    assertThat(e.occurredAt()).isEqualTo(NOW.minusMillis(2500));
                }));
    }

    @Test
    void recordingAndInboundAndInstructions() {
        String recording = "RE" + "3".repeat(32);
        assertThat(adapter.parse(request(query("k", "recording", "ref", ref.toString()), form("CallSid", PARENT,
                "RecordingSid", recording, "RecordingStatus", "completed", "RecordingDuration", "42"), TOKEN)))
                .singleElement().satisfies(e -> {
                    assertThat(e.type()).isEqualTo(Type.RECORDING_AVAILABLE);
                    assertThat(e.recordingRef()).isEqualTo(recording);
                    assertThat(e.recordingDurationSeconds()).isEqualTo(42);
                });
        assertThat(adapter.parse(request(query("k", "recording"), form("CallSid", PARENT, "RecordingSid", recording,
                "RecordingStatus", "absent"), TOKEN))).isEmpty();

        WebhookRequest inbound = request(query("k", "inbound"), form("CallSid", PARENT, "From", "+5511987654321",
                "Direction", "inbound", "CallStatus", "ringing"), TOKEN);
        assertThat(adapter.parse(inbound)).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo(Type.INBOUND);
            assertThat(e.from()).isEqualTo("+5511987654321");
            assertThat(e.awaitingInstructions()).isTrue();
        });
        assertThat(adapter.instructionRequest(inbound)).hasValueSatisfying(r -> {
            assertThat(r.kind()).isEqualTo(Kind.CALL_ANSWERED);
            assertThat(r.providerCallId()).isEqualTo(PARENT);
        });
        String connect = adapter.render(inbound, new CallInstruction.ConnectAgent("client:op_x", "+551130000000", true,
                true)).body();
        assertThat(connect).contains("<Client>op_x</Client>").contains("record=\"record-from-answer-dual\"")
                .contains("<Say voice=\"Polly.Camila\" language=\"pt-BR\">").contains("k=recording");
        assertThat(adapter.render(inbound, new CallInstruction.Hangup(true)).body()).contains("<Say").contains("<Hangup/>");
        assertThat(adapter.render(inbound, new CallInstruction.Hangup(false)).body()).doesNotContain("<Say");

        WebhookRequest announce = request(query("k", "announce", "ref", ref.toString()), form("CallSid", CHILD), TOKEN);
        assertThat(adapter.parse(announce)).isEmpty();
        assertThat(adapter.instructionRequest(announce)).hasValueSatisfying(r -> assertThat(r.kind()).isEqualTo(Kind.ANNOUNCE));
        assertThat(adapter.render(announce, new CallInstruction.Announce()).body()).contains("pode ser gravada")
                .doesNotContain("<Dial");
        assertThat(adapter.instructionRequest(request(query("k", "status"), form("CallSid", PARENT), TOKEN))).isEmpty();
    }

    @Test
    void malformedBodiesAreRejected() {
        assertThatThrownBy(() -> adapter.parse(new WebhookRequest(BASE, Map.of("k", "status"), Map.of(),
                "CallStatus=ringing".getBytes(StandardCharsets.UTF_8), null, NOW))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.parse(new WebhookRequest(BASE, Map.of("k", "status"), Map.of(),
                "%zz=1".getBytes(StandardCharsets.UTF_8), null, NOW))).isInstanceOf(IllegalArgumentException.class);
        assertThat(adapter.consoleEndpoints()).extracting(c -> c.suffix()).contains("?k=inbound");
    }
}
