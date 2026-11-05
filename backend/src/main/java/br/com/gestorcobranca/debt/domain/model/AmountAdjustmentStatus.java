package br.com.gestorcobranca.debt.domain.model;

/** Situação do pedido de correção do valor do título. */
public enum AmountAdjustmentStatus {
    /** Acima do limite ou aumento do saldo: aguarda outra pessoa com {@code TITLE_AMOUNT_APPROVE}. */
    PENDING_APPROVAL,
    APPLIED,
    REJECTED,
    /** Retirado por quem pediu antes da decisão. */
    CANCELLED;

    public boolean isFinal() {
        return this != PENDING_APPROVAL;
    }
}
