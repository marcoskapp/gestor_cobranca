package br.com.gestorcobranca.ingestion.domain.model.pdf;

import java.util.Collection;

/**
 * Origem de um valor no PDF (rastreio): página, linha e a caixa em pontos, com origem no canto superior esquerdo.
 * A tela de validação destaca essa região na imagem da página.
 */
public record SourceRef(int page, int line, double x0, double top, double x1, double bottom) {

    public SourceRef {
        x0 = round(x0);
        top = round(top);
        x1 = round(x1);
        bottom = round(bottom);
    }

    public static SourceRef of(int page, int line, Collection<TextWord> words) {
        double x0 = Double.MAX_VALUE;
        double top = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE;
        double bottom = -Double.MAX_VALUE;
        for (TextWord w : words) {
            x0 = Math.min(x0, w.x0());
            top = Math.min(top, w.top());
            x1 = Math.max(x1, w.x1());
            bottom = Math.max(bottom, w.bottom());
        }
        return new SourceRef(page, line, x0, top, x1, bottom);
    }

    /** Caixa que cobre as duas regiões (devem estar na mesma página). */
    public SourceRef union(SourceRef other) {
        if (other == null) {
            return this;
        }
        return new SourceRef(page, Math.min(line, other.line), Math.min(x0, other.x0), Math.min(top, other.top),
                Math.max(x1, other.x1), Math.max(bottom, other.bottom));
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
