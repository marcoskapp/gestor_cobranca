package br.com.gestorcobranca.collection.domain.model;

/** Envio de um e-mail ao credor (o SMTP não informa entrega: aceito pelo servidor = enviado). */
public enum DisputeMailStatus {
    QUEUED,
    SENT,
    FAILED,
    /** Lembrete ou cobrança não enviado porque o pedido foi respondido ou cancelado antes. */
    SKIPPED
}
