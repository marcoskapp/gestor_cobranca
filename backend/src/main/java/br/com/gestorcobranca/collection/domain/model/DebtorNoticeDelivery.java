package br.com.gestorcobranca.collection.domain.model;

/** Situação da resposta ao devedor: na fila, enviada, entregue, lida, falha, bloqueada pelo porteiro de contato ou registrada (outro meio). */
public enum DebtorNoticeDelivery {
    QUEUED,
    SENT,
    DELIVERED,
    READ,
    FAILED,
    BLOCKED,
    REGISTERED
}
