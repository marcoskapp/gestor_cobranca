package br.com.gestorcobranca.collection.domain.model;

/** E-mail de um pedido ao credor: o pedido, o lembrete antes do prazo e a cobrança do retorno vencido. */
public enum DisputeMailPurpose {
    REQUEST,
    REMINDER,
    OVERDUE
}
