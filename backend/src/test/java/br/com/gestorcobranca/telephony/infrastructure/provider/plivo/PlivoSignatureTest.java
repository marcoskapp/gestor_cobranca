package br.com.gestorcobranca.telephony.infrastructure.provider.plivo;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlivoSignatureTest {

    private static final String TOKEN = "MjU1YTU1ZGQ0ZTQyNTFlYzQ3MDg";

    /**
     * Exemplo da documentação oficial ("Signature validation-V3"): URL com consulta, parâmetros do
     * POST ordenados e nonce {@code kjsdhfsd87sd7yisud2}. A cadeia montada é a publicada; o HMAC
     * esperado foi calculado à parte com HMAC-SHA256 padrão sobre {@code cadeia + "." + nonce}.
     */
    @Test
    void documentedBaseStringAndSignature() {
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("Digits", List.of("1234"));
        params.put("To", List.of("+15555555555"));
        params.put("From", List.of("+15551111111"));
        params.put("CallUUID", List.of("4vbcpem8-0u46-x1ha-9af1-438vc92bf374"));
        String base = PlivoSignature.baseString("https://example.com/abcd?foo=bar", params);
        assertThat(base).isEqualTo("https://example.com/abcd?foo=bar.CallUUID4vbcpem8-0u46-x1ha-9af1-438vc92bf374"
                + "Digits1234From+15551111111To+15555555555");
        String signature = PlivoSignature.sign(TOKEN, base, "kjsdhfsd87sd7yisud2");
        assertThat(signature).isEqualTo("VbLQPTCCENsfMlEhTi4G++RXQOjeCV7YNORoj12RsJE=");
        assertThat(PlivoSignature.valid(TOKEN, "https://example.com/abcd?foo=bar", params, "kjsdhfsd87sd7yisud2",
                signature)).isTrue();
        // várias assinaturas (tokens ativos): vale qualquer uma
        assertThat(PlivoSignature.valid(TOKEN, "https://example.com/abcd?foo=bar", params, "kjsdhfsd87sd7yisud2",
                "outra=, " + signature)).isTrue();
        assertThat(PlivoSignature.valid(TOKEN, "https://example.com/abcd?foo=bar", params, "outro-nonce", signature))
                .isFalse();
        assertThat(PlivoSignature.valid("outro-token", "https://example.com/abcd?foo=bar", params, "kjsdhfsd87sd7yisud2",
                signature)).isFalse();
    }

    @Test
    void queryIsReorderedAndUrlWithoutQueryGetsTheSeparator() {
        Map<String, List<String>> params = new LinkedHashMap<>();
        params.put("RequestUUID", List.of("rq-1"));
        params.put("CallUUID", List.of("cu-1"));
        params.put("Direction", List.of("outbound"));
        String url = "https://gestor.teste/api/v1/webhooks/telephony/abc?ref=7f4c1a52-0000-4000-8000-000000000001"
                + "&k=answer&leg=dialer";
        assertThat(PlivoSignature.baseString(url, params)).isEqualTo("https://gestor.teste/api/v1/webhooks/telephony/abc"
                + "?k=answer&leg=dialer&ref=7f4c1a52-0000-4000-8000-000000000001.CallUUIDcu-1DirectionoutboundRequestUUIDrq-1");
        assertThat(PlivoSignature.sign(TOKEN, PlivoSignature.baseString(url, params), "n1"))
                .isEqualTo("JsLtT+Kpu29NKKUkAfqFpEHljzoISZ8l3/CN81dSAXU=");
        assertThat(PlivoSignature.baseString("https://h.teste/p", params))
                .isEqualTo("https://h.teste/p?CallUUIDcu-1DirectionoutboundRequestUUIDrq-1");
        assertThat(PlivoSignature.baseString("https://h.teste/p?b=2&a=1", Map.of())).isEqualTo("https://h.teste/p?a=1&b=2");
        assertThat(PlivoSignature.baseString("https://h.teste:8443/p", Map.of())).isEqualTo("https://h.teste:8443/p");
        assertThat(PlivoSignature.valid(TOKEN, url, params, null, "x")).isFalse();
    }
}
