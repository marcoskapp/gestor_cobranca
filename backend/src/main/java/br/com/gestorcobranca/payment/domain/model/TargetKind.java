package br.com.gestorcobranca.payment.domain.model;

/** Destino de uma alocação: parcelas de um acordo, títulos avulsos ou devolução ao pagador. */
public enum TargetKind {
    AGREEMENT, TITLES, REFUND
}
