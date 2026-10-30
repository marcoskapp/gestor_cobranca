package br.com.gestorcobranca.ingestion.domain.model.pdf;

import java.util.List;

/**
 * Camada de texto posicionada de um PDF: o insumo do intérprete do mapa de extração. Contém dados pessoais do
 * devedor: nunca é gravada nem registrada em log; é lida do arquivo original quando necessário.
 */
public record PdfText(List<TextPage> pages) {

    /** Mínimo de palavras por página para considerar que o PDF tem camada de texto utilizável. */
    public static final int MIN_WORDS_PER_PAGE = 5;

    public PdfText {
        pages = List.copyOf(pages);
    }

    public int wordCount() {
        return pages.stream().mapToInt(TextPage::wordCount).sum();
    }

    /**
     * Há camada de texto quando a maioria das páginas tem texto (um PDF escaneado costuma ter, no máximo, um
     * carimbo ou número de página em texto).
     */
    public boolean hasTextLayer() {
        if (pages.isEmpty()) {
            return false;
        }
        long withText = pages.stream().filter(p -> p.wordCount() >= MIN_WORDS_PER_PAGE).count();
        return withText * 2 > pages.size();
    }

    /** Linhas de todas as páginas na ordem de leitura. */
    public List<TextLine> lines() {
        return pages.stream().flatMap(p -> p.lines().stream()).toList();
    }
}
