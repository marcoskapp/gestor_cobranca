package br.com.gestorcobranca.debt.domain.model;

/** Natureza do bem dado em garantia, que define como ele é identificado. */
public enum AssetKind {
    /** Placa, RENAVAM, chassi, ano de fabricação e modelo. */
    VEHICLE,
    /** Matrícula, cartório de registro de imóveis, município e UF. */
    REAL_ESTATE,
    /** Demais bens e direitos: identificados pela descrição da garantia. */
    OTHER
}
