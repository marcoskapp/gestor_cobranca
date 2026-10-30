package br.com.gestorcobranca.ingestion.domain.model.pdf;

/**
 * Palavra da camada de texto de uma página do PDF, com a caixa em pontos (1/72 pol.) e origem no canto superior
 * esquerdo da página (o eixo y cresce para baixo, como na leitura).
 *
 * @param text   texto exatamente como está no PDF (sem espaços)
 * @param x0     borda esquerda
 * @param x1     borda direita
 * @param top    topo
 * @param bottom base
 */
public record TextWord(String text, double x0, double x1, double top, double bottom) {

    public TextWord {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Palavra vazia");
        }
        if (x1 < x0) {
            x1 = x0;
        }
        if (bottom < top) {
            bottom = top;
        }
    }

    public double center() {
        return (x0 + x1) / 2;
    }

    public double width() {
        return x1 - x0;
    }

    public double height() {
        return bottom - top;
    }

    /** Largura média de um caractere (base das tolerâncias de espaçamento). */
    public double charWidth() {
        return width() / Math.max(1, text.codePointCount(0, text.length()));
    }
}
