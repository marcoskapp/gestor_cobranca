package br.com.gestorcobranca.ingestion.domain.model.format;

import java.math.BigDecimal;

/**
 * Efeito de uma interpretação na coluna inteira.
 *
 * @param total        soma dos valores lidos (tipados e em texto)
 * @param invalidCount valores que essa interpretação não consegue ler
 */
public record OptionSummary(FormatOption option, BigDecimal total, long invalidCount) {
}
