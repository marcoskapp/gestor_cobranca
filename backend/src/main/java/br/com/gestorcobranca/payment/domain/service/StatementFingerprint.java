package br.com.gestorcobranca.payment.domain.service;

import br.com.gestorcobranca.shared.domain.Money;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Impressão digital de uma transação de extrato: data, valor, sentido, identificador da transação e
 * referência normalizados, mais a ocorrência dentro do arquivo (linhas idênticas sem identificador
 * são transações distintas no mesmo arquivo, mas a mesma transação quando reaparecem noutro arquivo
 * com extrato sobreposto). Com identificador da transação, ele sozinho (mais o sentido) identifica a linha.
 */
public final class StatementFingerprint {

    private StatementFingerprint() {
    }

    public static String of(LocalDate paidOn, Money amount, boolean credit, String transactionId, String reference,
                            int occurrence) {
        String key = transactionId != null && !transactionId.isBlank()
                ? "tx|" + (credit ? "C" : "D") + "|" + transactionId.trim().toUpperCase(Locale.ROOT)
                : String.join("|", "ln", paidOn.toString(), amount.amount().toPlainString(), credit ? "C" : "D",
                reference == null ? "" : reference.trim().toUpperCase(Locale.ROOT), Integer.toString(occurrence));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
