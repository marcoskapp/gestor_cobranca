package br.com.gestorcobranca.scoring.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

/**
 * Pontos de corte das faixas sobre o score 0–1000: A ≥ {@code a} &gt; B ≥ {@code b} &gt; C ≥
 * {@code c} &gt; D ≥ {@code d} &gt; E. Fixados em cada versão de modelo.
 */
public record BandCutoffs(int a, int b, int c, int d) {

    public static final BandCutoffs DEFAULT = new BandCutoffs(400, 250, 150, 75);

    public BandCutoffs {
        Guard.require(a <= 1000 && a > b && b > c && c > d && d > 0, "scoring.band_cutoffs_invalid",
                "Cortes das faixas devem ser decrescentes entre 1 e 1000 (A > B > C > D)");
    }

    public String band(int score) {
        if (score >= a) {
            return "A";
        }
        if (score >= b) {
            return "B";
        }
        if (score >= c) {
            return "C";
        }
        return score >= d ? "D" : "E";
    }

    public static final java.util.List<String> BANDS = java.util.List.of("A", "B", "C", "D", "E");
}
