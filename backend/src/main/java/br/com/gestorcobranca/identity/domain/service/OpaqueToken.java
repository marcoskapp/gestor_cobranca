package br.com.gestorcobranca.identity.domain.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Token opaco de alta entropia (256 bits) usado para refresh e links de ação. Formato:
 * {@code <tenantId base64url>.<segredo base64url>}. O prefixo do tenant permite localizar o
 * registro sob RLS antes de haver usuário autenticado; não é segredo. Apenas o SHA-256 do token
 * completo é persistido.
 */
public record OpaqueToken(UUID tenantId, String value, String hash) {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public static OpaqueToken generate(UUID tenantId) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        ByteBuffer tenantBytes = ByteBuffer.allocate(16)
                .putLong(tenantId.getMostSignificantBits()).putLong(tenantId.getLeastSignificantBits());
        String value = ENCODER.encodeToString(tenantBytes.array()) + "." + ENCODER.encodeToString(secret);
        return new OpaqueToken(tenantId, value, sha256(value));
    }

    /** Interpreta um token recebido; vazio se o formato for inválido. */
    public static Optional<OpaqueToken> parse(String value) {
        if (value == null || value.length() > 200) {
            return Optional.empty();
        }
        int dot = value.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        try {
            byte[] tenantBytes = DECODER.decode(value.substring(0, dot));
            byte[] secret = DECODER.decode(value.substring(dot + 1));
            if (tenantBytes.length != 16 || secret.length != 32) {
                return Optional.empty();
            }
            ByteBuffer buffer = ByteBuffer.wrap(tenantBytes);
            UUID tenantId = new UUID(buffer.getLong(), buffer.getLong());
            return Optional.of(new OpaqueToken(tenantId, value, sha256(value)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String toString() {
        return "OpaqueToken[tenant=" + tenantId + "]";
    }
}
