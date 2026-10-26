package br.com.gestorcobranca.telephony.infrastructure.provider.alvaria;

import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent;
import br.com.gestorcobranca.telephony.infrastructure.provider.TimestampedHmac;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Entrada de eventos da Alvaria no formato canônico assinado (provisório até a confirmação do contrato). */
class AlvariaWebhookAdapterTest {

    private static final Instant NOW = Instant.parse("2026-10-26T15:00:00Z");
    private static final Map<String, String> CREDENTIALS = Map.of("webhookSecret", "segredo-alvaria");

    private final AlvariaWebhookAdapter adapter = new AlvariaWebhookAdapter(new JsonCodec(JsonMapper.builder().build()),
            new TelephonyProperties(null, null, null, null, null, null, null, null, null, null, null, null, null));

    private static byte[] body(UUID reference) {
        return ("{\"events\":[{\"id\":\"ev-1\",\"type\":\"answered\",\"callId\":\"alv-1\",\"reference\":\"" + reference
                + "\",\"timestamp\":\"2026-10-26T14:59:58Z\",\"amd\":\"HUMAN\"},"
                + "{\"id\":\"ev-2\",\"type\":\"COMPLETED\",\"callId\":\"alv-1\",\"timestamp\":\"2026-10-26T15:00:00Z\","
                + "\"hangupBy\":\"DEBTOR\"}]}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void acceptsSignatureWithinToleranceAndTranslatesEvents() {
        UUID reference = UUID.randomUUID();
        byte[] body = body(reference);
        String header = TimestampedHmac.header("segredo-alvaria", NOW.getEpochSecond() - 10, body);

        assertThat(adapter.validSignature(CREDENTIALS, Map.of("x-gestor-signature", header), body, NOW)).isTrue();
        List<ProviderCallEvent> events = adapter.parse(body);
        assertThat(events).extracting(ProviderCallEvent::type)
                .containsExactly(ProviderCallEvent.Type.ANSWERED, ProviderCallEvent.Type.COMPLETED);
        assertThat(events.getFirst().callReference()).isEqualTo(reference);
        assertThat(events.getFirst().amd()).isEqualTo("HUMAN");
        assertThat(events.get(1).hangupBy()).isEqualTo("DEBTOR");
    }

    @Test
    void refusesWrongSecretTamperedBodyReplayAndMissingHeader() {
        byte[] body = body(UUID.randomUUID());
        String header = TimestampedHmac.header("segredo-alvaria", NOW.getEpochSecond(), body);

        assertThat(adapter.validSignature(Map.of("webhookSecret", "outro"), Map.of("x-gestor-signature", header), body,
                NOW)).isFalse();
        assertThat(adapter.validSignature(CREDENTIALS, Map.of("x-gestor-signature", header),
                "{\"events\":[]}".getBytes(StandardCharsets.UTF_8), NOW)).isFalse();
        assertThat(adapter.validSignature(CREDENTIALS, Map.of("x-gestor-signature",
                TimestampedHmac.header("segredo-alvaria", NOW.getEpochSecond() - 3600, body)), body, NOW)).isFalse();
        assertThat(adapter.validSignature(CREDENTIALS, Map.of(), body, NOW)).isFalse();
    }

    @Test
    void malformedEventsAreRefused() {
        assertThatThrownBy(() -> adapter.parse("{\"events\":[{\"id\":\"x\",\"type\":\"TELEPORTED\"}]}"
                .getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.parse("nada".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
