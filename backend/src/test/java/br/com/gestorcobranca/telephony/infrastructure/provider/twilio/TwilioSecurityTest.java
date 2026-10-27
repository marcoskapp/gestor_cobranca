package br.com.gestorcobranca.telephony.infrastructure.provider.twilio;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TwilioSecurityTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * Vetor da documentação oficial ("Webhooks security"): Auth Token {@code 12345}, URL com consulta e
     * parâmetros do POST; assinatura publicada {@code L/OH5YylLD5NRKLltdqwSvS0BnU=}.
     */
    @Test
    void officialSignatureExampleMatches() {
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("CallSid", List.of("CA1234567890ABCDE"));
        params.put("Caller", List.of("+14158675310"));
        params.put("Digits", List.of("1234"));
        params.put("From", List.of("+14158675310"));
        params.put("To", List.of("+18005551212"));
        String url = "https://example.com/myapp.php?foo=1&bar=2";

        assertThat(TwilioSecurity.signature("12345", url, params)).isEqualTo("L/OH5YylLD5NRKLltdqwSvS0BnU=");
        assertThat(TwilioSecurity.validSignature("12345", url, params, "L/OH5YylLD5NRKLltdqwSvS0BnU=")).isTrue();
        // URL, parâmetro ou token diferentes invalidam
        assertThat(TwilioSecurity.validSignature("12345", "https://example.com/myapp.php?foo=1&bar=3", params,
                "L/OH5YylLD5NRKLltdqwSvS0BnU=")).isFalse();
        Map<String, List<String>> tampered = new LinkedHashMap<>(params);
        tampered.put("Digits", List.of("9999"));
        assertThat(TwilioSecurity.validSignature("12345", url, tampered, "L/OH5YylLD5NRKLltdqwSvS0BnU=")).isFalse();
        assertThat(TwilioSecurity.validSignature("54321", url, params, "L/OH5YylLD5NRKLltdqwSvS0BnU=")).isFalse();
        assertThat(TwilioSecurity.validSignature("12345", url, params, null)).isFalse();
        assertThat(TwilioSecurity.validSignature(null, url, params, "L/OH5YylLD5NRKLltdqwSvS0BnU=")).isFalse();
    }

    @Test
    void accessTokenFollowsTheDocumentedJwtLayout() throws Exception {
        Instant issued = Instant.parse("2026-10-26T12:00:00Z");
        String token = TwilioSecurity.accessToken("AC" + "a".repeat(32), "SK" + "b".repeat(32), "segredo-da-api-key",
                "op_x", null, issued, issued.plusSeconds(3600));
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        JsonNode header = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertThat(header.path("alg").asString()).isEqualTo("HS256");
        assertThat(header.path("typ").asString()).isEqualTo("JWT");
        assertThat(header.path("cty").asString()).isEqualTo("twilio-fpa;v=1");
        JsonNode payload = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(payload.path("iss").asString()).isEqualTo("SK" + "b".repeat(32));
        assertThat(payload.path("sub").asString()).isEqualTo("AC" + "a".repeat(32));
        assertThat(payload.path("exp").asLong()).isEqualTo(issued.getEpochSecond() + 3600);
        assertThat(payload.path("jti").asString()).startsWith("SK" + "b".repeat(32) + "-");
        assertThat(payload.path("grants").path("identity").asString()).isEqualTo("op_x");
        assertThat(payload.path("grants").path("voice").path("incoming").path("allow").asBoolean()).isTrue();
        assertThat(payload.path("grants").path("voice").has("outgoing")).isFalse();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("segredo-da-api-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8)));
        assertThat(parts[2]).isEqualTo(expected);

        String withApp = TwilioSecurity.accessToken("AC" + "a".repeat(32), "SK" + "b".repeat(32), "s", "op_x",
                "AP" + "c".repeat(32), issued, issued.plusSeconds(60));
        JsonNode appPayload = MAPPER.readTree(Base64.getUrlDecoder().decode(withApp.split("\\.")[1]));
        assertThat(appPayload.path("grants").path("voice").path("outgoing").path("application_sid").asString())
                .isEqualTo("AP" + "c".repeat(32));
    }
}
