package br.com.gestorcobranca.legal.domain.model;

/** Natureza da custa ou despesa jurídica. */
public enum ExpenseType {
    /** Custas iniciais e taxa judiciária. */
    COURT_FEES,
    /** Custas processuais posteriores (preparo, porte, cumprimento). */
    PROCEDURAL_COSTS,
    /** Diligência de oficial de justiça. */
    BAILIFF,
    /** Emolumentos de cartório (protesto, registro de imóveis, notas). */
    REGISTRY_FEES,
    /** Inclusão/baixa em birô de crédito. */
    CREDIT_BUREAU_FEES,
    APPRAISAL,
    AUCTION,
    PUBLICATION,
    CERTIFICATES,
    TRAVEL,
    OTHER
}
