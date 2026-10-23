package br.com.gestorcobranca.communication.domain.model;

/** Autor de uma mensagem. */
public enum SenderType {
    /** Operador humano. */
    USER,
    /** Assistente de IA (id = política do assistente). */
    ASSISTANT,
    /** Passo de campanha ou régua. */
    CAMPAIGN,
    /** Envio automático do sistema (lembretes, notificações). */
    SYSTEM,
    /** O próprio devedor (mensagem recebida). */
    DEBTOR
}
