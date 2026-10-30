package br.com.gestorcobranca.ingestion.domain.model.pdf;

/**
 * Linha que o mapa não soube classificar (nunca leva o texto, que pode ter dado pessoal).
 *
 * @param code {@code pdf.line_outside_record} (linha da tabela sem início de título) ou
 *             {@code pdf.line_page_preamble} (topo de página dentro da tabela, antes do cabeçalho repetido)
 */
public record LineNote(SourceRef source, String code) {
}
