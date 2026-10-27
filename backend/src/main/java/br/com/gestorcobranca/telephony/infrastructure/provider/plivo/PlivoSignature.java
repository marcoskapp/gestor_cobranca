package br.com.gestorcobranca.telephony.infrastructure.provider.plivo;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * {@code X-Plivo-Signature-V3} conforme a documentação oficial ("Signature validation") e o
 * utilitário oficial {@code validate_v3_signature} do SDK: HMAC-SHA256 com o <b>Auth Token</b> da
 * conta (ou subconta) dona da entidade sobre {@code <url>.<nonce>}, em Base64, onde {@code <url>} é:
 * <ul>
 *   <li>esquema + host (com porta, se houver) + caminho;</li>
 *   <li>{@code ?} + a consulta reordenada por nome ({@code nome=valor} unidos por {@code &}),
 *       quando há consulta — ou só {@code ?} quando não há consulta mas há parâmetros de POST;</li>
 *   <li>para POST com parâmetros: {@code .} (se havia consulta) + os parâmetros ordenados por nome
 *       (ordenação sensível a maiúsculas), concatenados como nome+valor, sem separadores.</li>
 * </ul>
 * O cabeçalho pode trazer várias assinaturas separadas por vírgula (vários tokens ativos): vale
 * qualquer uma. Comparação em tempo constante. O nonce é de uso único por requisição.
 */
final class PlivoSignature {

    private PlivoSignature() {
    }

    static String baseString(String url, Map<String, List<String>> postParams) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL inválida", e);
        }
        StringBuilder base = new StringBuilder(uri.getScheme()).append("://").append(uri.getRawAuthority())
                .append(uri.getRawPath() == null ? "" : uri.getRawPath());
        Map<String, List<String>> query = new TreeMap<>();
        String raw = uri.getRawQuery();
        if (raw != null && !raw.isEmpty()) {
            for (String pair : raw.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                query.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            }
        }
        StringJoiner joined = new StringJoiner("&");
        for (Map.Entry<String, List<String>> e : query.entrySet()) {
            List<String> values = new ArrayList<>(e.getValue());
            values.sort(null);
            for (String v : values) {
                joined.add(e.getKey() + "=" + v);
            }
        }
        boolean hasPost = postParams != null && !postParams.isEmpty();
        String queryText = joined.toString();
        if (!queryText.isEmpty() || hasPost) {
            base.append('?').append(queryText);
        }
        if (!queryText.isEmpty() && hasPost) {
            base.append('.');
        }
        if (hasPost) {
            for (Map.Entry<String, List<String>> e : new TreeMap<>(postParams).entrySet()) {
                List<String> values = new ArrayList<>(e.getValue());
                values.sort(null);
                for (String v : values) {
                    base.append(e.getKey()).append(v);
                }
            }
        }
        return base.toString();
    }

    static String sign(String authToken, String baseString, String nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal((baseString + "." + nonce)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean valid(String authToken, String url, Map<String, List<String>> postParams, String nonce,
                         String header) {
        if (authToken == null || authToken.isBlank() || url == null || nonce == null || nonce.isBlank()
                || header == null || header.isBlank()) {
            return false;
        }
        byte[] expected;
        try {
            expected = sign(authToken, baseString(url, postParams), nonce).getBytes(StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException e) {
            return false;
        }
        boolean match = false;
        for (String candidate : header.split(",")) {
            // sem curto-circuito: todas as candidatas são comparadas em tempo constante
            match |= MessageDigest.isEqual(expected, candidate.trim().getBytes(StandardCharsets.US_ASCII));
        }
        return match;
    }
}
