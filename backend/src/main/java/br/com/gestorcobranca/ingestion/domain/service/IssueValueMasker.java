package br.com.gestorcobranca.ingestion.domain.service;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.shared.logging.SensitiveDataMasker;

/**
 * Trecho do valor exibido no relatório de erros. Nunca expõe dado pessoal:
 * <ul>
 *   <li>campos pessoais (documento, nome, nascimento, telefones, e-mails, endereço): todas as
 *       letras e dígitos são ocultados, exceto os dois últimos caracteres;</li>
 *   <li>demais campos: {@link SensitiveDataMasker} (oculta CPF, CNPJ, e-mail ou telefone que
 *       apareçam por engano em qualquer coluna);</li>
 *   <li>limitado a 60 caracteres.</li>
 * </ul>
 */
public final class IssueValueMasker {

    static final int MAX_LENGTH = 60;
    private static final int VISIBLE_SUFFIX = 2;

    private IssueValueMasker() {
    }

    public static String excerpt(CanonicalField field, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        if (value.length() > MAX_LENGTH) {
            value = value.substring(0, MAX_LENGTH);
        }
        if (field == null || !field.personal()) {
            return SensitiveDataMasker.mask(value);
        }
        int visibleFrom = Math.max(0, value.length() - VISIBLE_SUFFIX);
        StringBuilder masked = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            masked.append(i < visibleFrom && Character.isLetterOrDigit(c) ? '*' : c);
        }
        return masked.toString();
    }
}
