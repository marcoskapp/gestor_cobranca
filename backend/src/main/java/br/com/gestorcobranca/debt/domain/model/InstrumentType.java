package br.com.gestorcobranca.debt.domain.model;

/** Natureza do instrumento da dívida, que define a ação cabível e a regra de prescrição. */
public enum InstrumentType {
    CONTRACT,
    /** Cédula de Crédito Bancário. */
    CCB,
    PROMISSORY_NOTE,
    DUPLICATE,
    CHECK,
    DEBT_CONFESSION,
    OTHER
}
