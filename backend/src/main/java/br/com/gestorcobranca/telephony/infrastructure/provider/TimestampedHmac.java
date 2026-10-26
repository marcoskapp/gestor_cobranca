package br.com.gestorcobranca.telephony.infrastructure.provider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Assinatura de webhook com instante assinado, no formato mais comum de CPaaS:
 * {@code t=<epoch s>,v1=<hex>} com {@code v1 = HMAC-SHA256(segredo, t + "." + corpo)}. Fora da
 * tolerância → inválida (replay). Comparação em tempo constante. Usada pelo formato canônico de
 * eventos do Gestor (conectores de provedores que não assinam por conta própria).
 */
public final class TimestampedHmac {

    private TimestampedHmac() {
    }

    public static boolean valid(String secret, String header, byte[] body, Instant now, Duration tolerance) {
        if (secret == null || secret.isBlank() || header == null || body == null) {
            return false;
        }
        Long timestamp = null;
        byte[] received = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            try {
                if ("t".equals(kv[0])) {
                    timestamp = Long.parseLong(kv[1].trim());
                } else if ("v1".equals(kv[0])) {
                    received = HexFormat.of().parseHex(kv[1].trim().toLowerCase(Locale.ROOT));
                }
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        if (timestamp == null || received == null) {
            return false;
        }
        Instant signedAt = Instant.ofEpochSecond(timestamp);
        if (signedAt.isBefore(now.minus(tolerance)) || signedAt.isAfter(now.plus(tolerance))) {
            return false;
        }
        return MessageDigest.isEqual(sign(secret, timestamp, body), received);
    }

    public static byte[] sign(String secret, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Cabeçalho pronto ({@code t=…,v1=…}); uso de testes e conectores. */
    public static String header(String secret, long timestamp, byte[] body) {
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(sign(secret, timestamp, body));
    }
}
