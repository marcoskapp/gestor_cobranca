package br.com.gestorcobranca.debt.domain.model;

/** Data a partir da qual o prazo de prescrição é contado. */
public enum PrescriptionBase {
    /** Vencimento do próprio título (parcela). */
    TITLE_DUE_DATE,
    /** Vencimento da última parcela do contrato (mesmo que haja vencimento antecipado). */
    CONTRACT_FINAL_DUE_DATE
}
