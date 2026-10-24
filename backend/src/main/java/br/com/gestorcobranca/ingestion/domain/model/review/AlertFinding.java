package br.com.gestorcobranca.ingestion.domain.model.review;

import java.math.BigDecimal;

/**
 * Alerta calculado para uma linha (ou para o arquivo, linha 0).
 *
 * @param priority          ordem na lista (maior primeiro)
 * @param field             coluna canônica envolvida
 * @param amount            valor que motivou o alerta
 * @param expectedLow       limite inferior da faixa típica (quando estatístico)
 * @param expectedHigh      limite superior da faixa típica (quando estatístico)
 * @param reference         distribuição usada como referência (quando estatístico)
 * @param referenceSize     tamanho da amostra da referência
 * @param suggestedExponent {@code n} quando o valor × 10ⁿ cai na faixa típica (provável separador decimal)
 * @param detail            explicação em linguagem simples
 */
public record AlertFinding(long rowNumber, AlertType type, int priority, String field, BigDecimal amount,
                           BigDecimal expectedLow, BigDecimal expectedHigh, ReferenceKind reference, Long referenceSize,
                           Integer suggestedExponent, String detail) {

    public AlertFinding atRow(long row) {
        return new AlertFinding(row, type, priority, field, amount, expectedLow, expectedHigh, reference, referenceSize,
                suggestedExponent, detail);
    }
}
