package br.com.gestorcobranca.telephony.infrastructure.provider.twilio;

import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Criptografia da Twilio, conforme a documentação oficial:
 * <ul>
 *   <li><b>{@code X-Twilio-Signature}</b> ("Webhooks security"): HMAC-SHA1 com o <b>Auth Token</b>
 *       da conta sobre a URL completa (com a consulta, exatamente como configurada; em HTTPS sem
 *       porta) seguida dos parâmetros do POST ordenados por nome (ordenação sensível a maiúsculas)
 *       e concatenados como nome+valor, sem separadores; resultado em Base64;</li>
 *   <li><b>Access Token</b> ("Access Tokens"): JWT HS256 assinado com o segredo da API Key,
 *       cabeçalho {@code cty: twilio-fpa;v=1}, {@code iss} = SID da API Key, {@code sub} = SID da
 *       conta, {@code grants.identity} e {@code grants.voice} ({@code incoming.allow},
 *       {@code outgoing.application_sid}); validade máxima de 24 h.</li>
 * </ul>
 */
final class TwilioSecurity {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private TwilioSecurity() {
    }

    static String signature(String authToken, String url, Map<String, List<String>> params) {
        StringBuilder data = new StringBuilder(url);
        Map<String, List<String>> sorted = new TreeMap<>(params);
        for (Map.Entry<String, List<String>> e : sorted.entrySet()) {
            List<String> values = new ArrayList<>(e.getValue());
            values.sort(null);
            for (String v : values) {
                data.append(e.getKey()).append(v);
            }
        }
        return Base64.getEncoder().encodeToString(hmac("HmacSHA1", authToken, data.toString()));
    }

    static boolean validSignature(String authToken, String url, Map<String, List<String>> params, String header) {
        if (authToken == null || authToken.isBlank() || url == null || header == null || header.isBlank()) {
            return false;
        }
        byte[] expected = signature(authToken, url, params).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, header.trim().getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * @param twimlAppSid TwiML App das chamadas de saída do navegador (nulo = só receber)
     */
    static String accessToken(String accountSid, String apiKeySid, String apiKeySecret, String identity,
                              String twimlAppSid, Instant issuedAt, Instant expiresAt) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("typ", "JWT");
        header.put("alg", "HS256");
        header.put("cty", "twilio-fpa;v=1");
        Map<String, Object> voice = new LinkedHashMap<>();
        voice.put("incoming", Map.of("allow", true));
        if (twimlAppSid != null && !twimlAppSid.isBlank()) {
            voice.put("outgoing", Map.of("application_sid", twimlAppSid));
        }
        Map<String, Object> grants = new LinkedHashMap<>();
        grants.put("identity", identity);
        grants.put("voice", voice);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jti", apiKeySid + "-" + issuedAt.getEpochSecond());
        payload.put("iss", apiKeySid);
        payload.put("sub", accountSid);
        payload.put("iat", issuedAt.getEpochSecond());
        payload.put("nbf", issuedAt.getEpochSecond());
        payload.put("exp", expiresAt.getEpochSecond());
        payload.put("grants", grants);
        String signingInput = URL.encodeToString(JSON.writeValueAsBytes(header)) + "."
                + URL.encodeToString(JSON.writeValueAsBytes(payload));
        return signingInput + "." + URL.encodeToString(hmac("HmacSHA256", apiKeySecret, signingInput));
    }

    private static byte[] hmac(String algorithm, String key, String data) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), algorithm));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
