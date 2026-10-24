package br.com.gestorcobranca.ingestion.domain.model.format;

/** Motivo pelo qual um valor monetário não pôde ser lido. */
public enum AmountProblem {
    /** Separadores fora do padrão, símbolos ou letras. */
    FORMAT,
    /** Mais de duas casas decimais não nulas. */
    TOO_MANY_DECIMALS,
    /** Notação científica ({@code 1e3}, {@code 1,23E+11}). */
    SCIENTIFIC_NOTATION,
    /** Mais dígitos inteiros do que um valor monetário admite. */
    TOO_LARGE
}
