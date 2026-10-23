package br.com.gestorcobranca.communication.domain.model;

/** Situação da primeira resposta de uma conversa aguardando o atendimento. */
public enum SlaStatus {
    /** Dentro da meta. */
    OK,
    /** Passou do aviso; ainda dentro do limite. */
    WARNING,
    /** Limite estourado. */
    BREACHED
}
