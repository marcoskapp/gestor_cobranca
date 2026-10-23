package br.com.gestorcobranca.payment.domain.model;

/** Tipo de divergência de conciliação. */
public enum DivergenceType {
    AMOUNT_MISMATCH, DUPLICATE_PAYMENT, UNMATCHED, NOT_CONFIRMED, PARTIAL_REFUND
}
