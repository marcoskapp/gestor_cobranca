package br.com.gestorcobranca.collection.domain.model;

/** Ordem em que a fila entrega os casos ao operador. */
public enum CaseOrdering {
    /** Maior atraso primeiro (vencimento mais antigo). */
    OLDEST_OVERDUE,
    /** Maior saldo primeiro. */
    HIGHEST_BALANCE,
    /** Menor saldo primeiro. */
    LOWEST_BALANCE,
    /** Casos trabalhados há mais tempo (ou nunca) primeiro. */
    LEAST_RECENTLY_WORKED,
    /** Maior score de propensão primeiro (casos sem score ao final). */
    HIGHEST_SCORE
}
