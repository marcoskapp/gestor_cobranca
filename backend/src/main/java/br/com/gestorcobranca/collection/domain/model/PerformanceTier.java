package br.com.gestorcobranca.collection.domain.model;

import java.util.Locale;

/**
 * Nível de desempenho do negociador, publicado pelo {@code incentive} (evento {@code NegotiatorTierChanged}) e
 * projetado no collection ({@link NegotiatorTier}). A é o melhor nível. O collection conhece só o nível — nunca a
 * nota, o quartil ou os valores que o compõem (distribuicao-por-desempenho.md §7).
 */
public enum PerformanceTier {
    A, B, C, D;

    /** @return nulo para texto vazio ou nulo */
    public static PerformanceTier parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
