package br.com.gestorcobranca.ingestion.domain.model.pdf;

import java.util.List;

/**
 * Linha física reconstruída de uma página: palavras na mesma faixa vertical, da esquerda para a direita.
 *
 * @param page   página (1..n)
 * @param number linha na página (1..n, de cima para baixo)
 */
public record TextLine(int page, int number, List<TextWord> words) {

    public TextLine {
        if (words == null || words.isEmpty()) {
            throw new IllegalArgumentException("Linha sem palavras");
        }
        words = List.copyOf(words);
    }

    public double top() {
        return words.stream().mapToDouble(TextWord::top).min().orElse(0);
    }

    public double bottom() {
        return words.stream().mapToDouble(TextWord::bottom).max().orElse(0);
    }

    public double x0() {
        return words.getFirst().x0();
    }

    public double x1() {
        return words.stream().mapToDouble(TextWord::x1).max().orElse(0);
    }

    /** Texto da linha com um espaço entre as palavras. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (TextWord w : words) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(w.text());
        }
        return sb.toString();
    }

    public SourceRef ref() {
        return new SourceRef(page, number, x0(), top(), x1(), bottom());
    }
}
