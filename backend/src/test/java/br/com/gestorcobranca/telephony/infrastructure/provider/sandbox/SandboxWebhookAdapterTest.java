package br.com.gestorcobranca.telephony.infrastructure.provider.sandbox;

import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxWebhookAdapterTest {

    static final String SECRET = "segredo-webhook";
    final SandboxWebhookAdapter adapter = new SandboxWebhookAdapter(new JsonCodec(JsonMapper.builder().build()),
            new TelephonyProperties(null, null, null, null, null, null, null, null, null, null));
    final Instant now = Instant.parse("2026-10-23T12:00:00Z");

    static String header(String secret, long t, byte[] body) {
        return "t=" + t + ",v1=" + HexFormat.of().formatHex(SandboxWebhookAdapter.sign(secret, t, body));
    }

    @Test
    void validatesHmacOverTimestampAndBody() {
        byte[] body = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);
        long t = now.getEpochSecond();
        Map<String, String> credentials = Map.of("webhookSecret", SECRET);
        assertThat(adapter.validSignature(credentials, Map.of("x-sandbox-signature", header(SECRET, t, body)), body, now))
                .isTrue();
        assertThat(adapter.validSignature(credentials, Map.of("x-sandbox-signature", header("outro", t, body)), body, now))
                .isFalse();
        byte[] tampered = "{\"events\":[{}]}".getBytes(StandardCharsets.UTF_8);
        assertThat(adapter.validSignature(credentials, Map.of("x-sandbox-signature", header(SECRET, t, body)), tampered,
                now)).isFalse();
        assertThat(adapter.validSignature(credentials, Map.of(), body, now)).isFalse();
        assertThat(adapter.validSignature(Map.of(), Map.of("x-sandbox-signature", header(SECRET, t, body)), body, now))
                .isFalse();
        assertThat(adapter.validSignature(credentials, Map.of("x-sandbox-signature", "t=abc,v1=zz"), body, now)).isFalse();
    }

    @Test
    void rejectsSignaturesOutsideTheTolerance() {
        byte[] body = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> credentials = Map.of("webhookSecret", SECRET);
        long old = now.minusSeconds(6 * 60).getEpochSecond();
        long future = now.plusSeconds(6 * 60).getEpochSecond();
        assertThat(adapter.validSignature(credentials, Map.of("x-sandbox-signature", header(SECRET, old, body)), body, now))
                .isFalse();
        assertThat(adapter.validSignature(credentials, Map.of("x-sandbox-signature", header(SECRET, future, body)), body,
                now)).isFalse();
    }

    @Test
    void parsesEventsAndRejectsMalformedBodies() {
        String json = """
                {"events":[{"id":"e1","type":"answered","callId":"sbx-call-1",
                  "reference":"0190f3a0-0000-7000-8000-000000000001","timestamp":"2026-10-23T12:00:00Z","amd":"HUMAN"},
                 {"id":"e2","type":"RECORDING_AVAILABLE","callId":"sbx-call-1","timestamp":"2026-10-23T12:01:00Z",
                  "recordingRef":"r1","recordingDuration":42}]}
                """;
        List<ProviderCallEvent> events = adapter.parse(json.getBytes(StandardCharsets.UTF_8));
        assertThat(events).hasSize(2);
        assertThat(events.getFirst().type()).isEqualTo(ProviderCallEvent.Type.ANSWERED);
        assertThat(events.getFirst().amd()).isEqualTo("HUMAN");
        assertThat(events.get(1).recordingDurationSeconds()).isEqualTo(42);
        assertThatThrownBy(() -> adapter.parse("nada".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.parse("{\"events\":[{\"id\":\"e\",\"type\":\"XYZ\"}]}"
                .getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
    }
}
