package br.com.gestorcobranca.collection.domain.model;

/**
 * E-mail do credor recebido para um pedido: aguardando o analista confirmar a posição, em quarentena (falhou em alguma
 * verificação; a gestão libera ou descarta), confirmado (virou resposta do credor) ou descartado com motivo.
 */
public enum MailReplyStatus {
    PENDING_CONFIRMATION,
    QUARANTINED,
    CONFIRMED,
    DISMISSED
}
