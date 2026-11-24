package br.com.gestorcobranca.settlement.application.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Texto canônico do termo do portal do credor (versão {@code v1}) e o SHA-256 dele: o hash prova o que foi decidido,
 * por quem, quando, com qual observação e sobre quais valores e arquivos. Mudar o formato exige nova versão.
 */
final class StatementTermDigest {

    private StatementTermDigest() {
    }

    /** @param fileSha256 hashes dos arquivos, já em ordem */
    static String canonical(UUID tenantId, UUID statementId, String decision, UUID creditorUserId, UUID sessionId,
                            Instant recordedAt, Instant mfaVerifiedAt, BigDecimal netTransfer, String note,
                            List<String> fileSha256) {
        return String.join("|", "v1", tenantId.toString(), statementId.toString(), decision, creditorUserId.toString(),
                String.valueOf(sessionId), recordedAt.toString(), String.valueOf(mfaVerifiedAt),
                netTransfer.toPlainString(), note == null ? "-" : sha256(note), String.join(",", fileSha256));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
