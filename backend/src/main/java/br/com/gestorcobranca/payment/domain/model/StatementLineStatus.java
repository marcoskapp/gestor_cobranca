package br.com.gestorcobranca.payment.domain.model;

/** Resultado da conciliação de uma linha de extrato. */
public enum StatementLineStatus {
    RECONCILED, CREATED_PAYMENT, DIVERGENT, ALREADY_RECONCILED, IGNORED_DUPLICATE, IGNORED_DEBIT
}
