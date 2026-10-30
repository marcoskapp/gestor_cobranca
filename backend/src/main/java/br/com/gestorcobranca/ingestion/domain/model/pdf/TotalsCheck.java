package br.com.gestorcobranca.ingestion.domain.model.pdf;

import java.math.BigDecimal;

/**
 * Conferência de um total de controle do documento com a soma (ou contagem) extraída.
 *
 * @param debtorIndex bloco conferido (-1 = documento inteiro)
 * @param label       rótulo do total no documento
 * @param column      coluna somada (nula = campo canônico)
 * @param target      campo canônico somado ou {@code quantidade}
 * @param document    valor impresso no documento (nulo se ilegível)
 * @param extracted   soma ou contagem dos títulos extraídos
 * @param records     títulos considerados
 */
public record TotalsCheck(int debtorIndex, String label, String column, String target, BigDecimal document,
                          BigDecimal extracted, BigDecimal difference, boolean matches, long records,
                          SourceRef source) {
}
