package br.com.gestorcobranca.sandbox.domain.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Código do autenticador (TOTP, RFC 6238: HMAC-SHA1, 6 dígitos, passo de 30 s) calculado pela carga do sandbox para a
 * pessoa do credor de demonstração, a partir do segredo que o próprio fluxo de ativação devolveu (opção D). É uma
 * implementação própria do sandbox — o módulo não depende do {@code identity} (Spring Modulith) e o servidor continua
 * conferindo com a dele; só gera (a verificação é do servidor). Conferido pelos vetores do RFC 6238 (SHA-1).
 */
public final class DemoTotp {

    public static final long STEP_SECONDS = 30;
    private static final int DIGITS = 6;

    private DemoTotp() {
    }

    /** Passo de tempo do instante (contador do RFC 6238 com T0 = 0). */
    public static long step(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), STEP_SECONDS);
    }

    /** Segundos que faltam para o passo do instante terminar (1 a 30). */
    public static long secondsLeft(Instant instant) {
        return STEP_SECONDS - Math.floorMod(instant.getEpochSecond(), STEP_SECONDS);
    }

    /** Código de 6 dígitos do passo, com o segredo em Base32 (como o do QR do autenticador). */
    public static String code(String base32Secret, long step) {
        return code(base32Decode(base32Secret), step, DIGITS);
    }

    /** Truncamento dinâmico do RFC 4226 sobre o HMAC-SHA1 do contador (8 bytes, big-endian). */
    static String code(byte[] key, long step, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            long modulus = (long) Math.pow(10, digits);
            return String.format(Locale.ROOT, "%0" + digits + "d", binary % modulus);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 indisponível", e);
        }
    }

    /** A mesma chave do segredo Base32, em hexadecimal (o {@code credor-codigo.sh} calcula o código com o OpenSSL). */
    public static String hex(String base32Secret) {
        return HexFormat.of().formatHex(base32Decode(base32Secret));
    }

    static byte[] base32Decode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("segredo Base32 vazio");
        }
        String clean = value.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        ByteArrayOutputStream out = new ByteArrayOutputStream(clean.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int index = c >= 'A' && c <= 'Z' ? c - 'A' : c >= '2' && c <= '7' ? c - '2' + 26 : -1;
            if (index < 0) {
                throw new IllegalArgumentException("caractere Base32 inválido");
            }
            buffer = (buffer << 5) | index;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
