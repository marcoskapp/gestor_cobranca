package br.com.gestorcobranca.debt.domain.model;

/** Situação da retenção da cobrança após o estorno de pagamento recebido pelo credor (H07 parte 3). */
public enum ReversalHoldState {
    /** Aguardando a conferência de uma pessoa: sem contato de cobrança do credor com o devedor. */
    PENDING,
    /** Liberada por uma pessoa com {@code PAYMENT_REVIEW}, com justificativa e confirmação. */
    RELEASED,
    /** Encerrada sem liberação: titular anonimizado ou eliminado (sem retomar contato). */
    ENDED
}
