package br.com.gestorcobranca.shared.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Encadeamento de hashes da trilha de auditoria (SHA-256, hexadecimal). O hash de conteúdo de
 * cada registro é calculado no banco ({@code shared.audit_content_hash}).
 */
public final class AuditHashing {

    /** Hash inicial da cadeia de cada tenant. */
    public static final String GENESIS = "0".repeat(64);

    private AuditHashing() {
    }

    public static String chainHash(String previousChainHash, String contentHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((previousChainHash + contentHash).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
