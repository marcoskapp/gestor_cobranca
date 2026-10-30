package br.com.gestorcobranca.ingestion.domain.model.pdf;

import java.util.List;

/**
 * Página com as linhas reconstruídas da camada de texto.
 *
 * @param number página (1..n)
 * @param width  largura em pontos
 * @param height altura em pontos
 */
public record TextPage(int number, double width, double height, List<TextLine> lines) {

    public TextPage {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    public int wordCount() {
        return lines.stream().mapToInt(l -> l.words().size()).sum();
    }
}
