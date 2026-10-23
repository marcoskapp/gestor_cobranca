package br.com.gestorcobranca.payment.domain.service;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Identificadores do arranjo Pix (Manual de Padrões para Iniciação do Pix / API Pix do BACEN):
 * {@code txid} de 26 a 35 caracteres alfanuméricos e {@code endToEndId} de 32 caracteres.
 * O {@code txid} (32 caracteres hexadecimais de um UUID aleatório) é fixado na instrução antes do
 * registro: {@code PUT /cob/{txid}} é idempotente, e uma repetição do job não cria outra cobrança.
 */
public final class PixIdentifiers {

    private static final Pattern TXID = Pattern.compile("[a-zA-Z0-9]{26,35}");
    private static final Pattern END_TO_END = Pattern.compile("[a-zA-Z0-9]{32}");

    private PixIdentifiers() {
    }

    public static String txidFor(UUID id) {
        return id.toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    public static boolean validTxid(String txid) {
        return txid != null && TXID.matcher(txid).matches();
    }

    public static boolean validEndToEndId(String endToEndId) {
        return endToEndId != null && END_TO_END.matcher(endToEndId).matches();
    }
}
