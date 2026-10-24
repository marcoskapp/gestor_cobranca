package br.com.gestorcobranca.ingestion.domain.model.format;

/**
 * Exemplo real do arquivo para a tela de formato (valores monetários não são dado pessoal).
 *
 * @param raw texto original da célula (ou o valor tipado em texto canônico)
 */
public record FormatSample(long rowNumber, String raw, AmountStyle style) {
}
