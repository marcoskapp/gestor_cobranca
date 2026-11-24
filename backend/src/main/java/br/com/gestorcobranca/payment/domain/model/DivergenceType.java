package br.com.gestorcobranca.payment.domain.model;

/**
 * Tipo de divergência de conciliação. {@code SELF_RECONCILIATION}: o extrato foi enviado por quem informou o pagamento
 * externo que ele cita — a conciliação fica para outra pessoa (SEG-20261124-C01).
 */
public enum DivergenceType {
    AMOUNT_MISMATCH, DUPLICATE_PAYMENT, UNMATCHED, NOT_CONFIRMED, PARTIAL_REFUND, SELF_RECONCILIATION
}
