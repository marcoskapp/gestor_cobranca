package br.com.gestorcobranca.collection.domain.model;

import java.util.Locale;

/**
 * Veredicto de uma verificação do e-mail recebido (SPF, DKIM, DMARC, vírus, spam), com os valores do Amazon SES.
 * {@code DISABLED} = verificação não feita (a assessoria nunca aceita vírus ou autenticação sem veredicto PASS).
 */
public enum MailVerdict {
    PASS,
    FAIL,
    GRAY,
    PROCESSING_FAILED,
    DISABLED;

    /** Valor do SES (ou do adaptador local); desconhecido ou ausente = {@code DISABLED}. */
    public static MailVerdict parse(String value) {
        if (value == null || value.isBlank()) {
            return DISABLED;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DISABLED;
        }
    }
}
