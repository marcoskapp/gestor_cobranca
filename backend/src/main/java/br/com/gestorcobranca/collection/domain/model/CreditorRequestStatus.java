package br.com.gestorcobranca.collection.domain.model;

/** Pedido ao credor: na fila de envio, enviado (aguardando retorno), falha no envio, respondido ou cancelado (a contestação foi encerrada sem resposta). */
public enum CreditorRequestStatus {
    QUEUED,
    SENT,
    FAILED,
    ANSWERED,
    CANCELLED
}
