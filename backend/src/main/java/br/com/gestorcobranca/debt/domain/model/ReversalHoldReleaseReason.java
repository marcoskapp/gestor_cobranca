package br.com.gestorcobranca.debt.domain.model;

/** Motivo da liberação da retenção, em lista curta (o detalhe vai na justificativa). */
public enum ReversalHoldReleaseReason {
    /** Credor confirmou que não recebeu o valor. */
    CREDITOR_CONFIRMED_NOT_RECEIVED,
    /** O pagamento tinha sido informado no título errado. */
    PAYMENT_ON_WRONG_TITLE,
    OTHER
}
