package br.com.gestorcobranca.ingestion.domain.model.pdf;

/**
 * Problema de um mapa de extração (proposto pela IA ou editado): impede a aplicação do mapa.
 *
 * @param code    código estável ({@code pdf_map.<motivo>})
 * @param path    onde está o problema no mapa (ex.: {@code table.columns[3].targets})
 */
public record MapProblem(String code, String path, String message) {
}
