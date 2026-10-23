package br.com.gestorcobranca.collection.domain.model;

/** Resultado da análise da contestação. */
public enum DisputeOutcome {
    /** Procedente: o devedor tem razão. */
    UPHELD,
    /** Improcedente: a cobrança segue. */
    REJECTED,
    /** Parcialmente procedente. */
    PARTIALLY_UPHELD
}
