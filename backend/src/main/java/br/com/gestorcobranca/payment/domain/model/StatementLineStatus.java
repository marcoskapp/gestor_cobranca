package br.com.gestorcobranca.payment.domain.model;

/**
 * Resultado da conciliação de uma linha de extrato. {@code PENDING_REVIEW}: a linha cita uma instrução sem pagamento e
 * criou um pagamento que aguarda a aprovação de outra pessoa; vira {@code CREATED_PAYMENT} na aprovação ou
 * {@code REVIEW_REJECTED} na recusa (SEG-20261124-C01).
 */
public enum StatementLineStatus {
    RECONCILED, CREATED_PAYMENT, DIVERGENT, ALREADY_RECONCILED, IGNORED_DUPLICATE, IGNORED_DEBIT, PENDING_REVIEW,
    REVIEW_REJECTED
}
