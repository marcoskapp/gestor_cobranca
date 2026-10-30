package br.com.gestorcobranca.ingestion.domain.model.pdf;

/**
 * Texto lido de uma região do PDF e a sua origem. O texto é exatamente o das palavras do PDF (unidas por um espaço):
 * nenhum valor é inventado ou corrigido.
 */
public record CellValue(String text, SourceRef source) {

    public boolean isBlank() {
        return text == null || text.isBlank();
    }
}
