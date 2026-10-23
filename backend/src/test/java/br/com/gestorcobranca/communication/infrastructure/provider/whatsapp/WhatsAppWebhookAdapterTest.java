package br.com.gestorcobranca.communication.infrastructure.provider.whatsapp;

import br.com.gestorcobranca.communication.application.port.out.ProviderWebhookAdapter.InboundBatch;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WhatsAppWebhookAdapterTest {

    private final WhatsAppWebhookAdapter adapter = new WhatsAppWebhookAdapter(new JsonCodec(JsonMapper.builder().build()));
    private final Map<String, String> credentials = Map.of("appSecret", "segredo-app", "verifyToken", "token-verif",
            "accessToken", "x");

    static final String PAYLOAD = """
            {"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"field":"messages","value":{
              "messaging_product":"whatsapp",
              "messages":[{"from":"5511987654321","id":"wamid.IN1","timestamp":"1761220000","type":"text","text":{"body":"SAIR"}},
                          {"from":"5511987654321","id":"wamid.IN2","timestamp":"1761220001","type":"interactive",
                           "interactive":{"button_reply":{"id":"b1","title":"Quero negociar"}}}],
              "statuses":[{"id":"wamid.OUT1","status":"delivered","timestamp":"1761220002","recipient_id":"5511987654321"},
                          {"id":"wamid.OUT2","status":"failed","timestamp":"1761220003",
                           "errors":[{"code":131026,"title":"Message undeliverable"}]}]}}]}]}
            """;

    static String sign(String secret, byte[] body) {
        return "sha256=" + HexFormat.of().formatHex(WhatsAppWebhookAdapter.hmac(secret, body));
    }

    @Test
    void validatesSignatureOverRawBodyInConstantTime() {
        byte[] body = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        assertThat(adapter.validSignature(credentials, sign("segredo-app", body), body)).isTrue();
        assertThat(adapter.validSignature(credentials, sign("outro", body), body)).isFalse();
        assertThat(adapter.validSignature(credentials, sign("segredo-app", body), (PAYLOAD + " ").getBytes())).isFalse();
        assertThat(adapter.validSignature(credentials, "sha256=zz", body)).isFalse();
        assertThat(adapter.validSignature(credentials, null, body)).isFalse();
        assertThat(adapter.validSignature(Map.of(), sign("segredo-app", body), body)).isFalse();
    }

    @Test
    void verifyToken() {
        assertThat(adapter.validVerifyToken(credentials, "token-verif")).isTrue();
        assertThat(adapter.validVerifyToken(credentials, "token-errado")).isFalse();
        assertThat(adapter.validVerifyToken(credentials, null)).isFalse();
    }

    @Test
    void parsesMessagesAndStatuses() {
        InboundBatch batch = adapter.parse(PAYLOAD.getBytes(StandardCharsets.UTF_8));
        assertThat(batch.messages()).hasSize(2);
        assertThat(batch.messages().get(0).text()).isEqualTo("SAIR");
        assertThat(batch.messages().get(0).timestamp()).isEqualTo(Instant.ofEpochSecond(1761220000));
        assertThat(batch.messages().get(1).text()).isEqualTo("Quero negociar");
        assertThat(batch.statuses()).extracting(s -> s.status()).containsExactly("DELIVERED", "FAILED");
        assertThat(batch.statuses().get(1).errorCode()).isEqualTo("131026");
    }

    @Test
    void malformedBodyIsRejected() {
        assertThatThrownBy(() -> adapter.parse("não é json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.parse("[]".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
