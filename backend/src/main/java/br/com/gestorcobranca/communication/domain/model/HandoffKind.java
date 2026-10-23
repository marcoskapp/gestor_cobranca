package br.com.gestorcobranca.communication.domain.model;

/** Natureza da mudança de dono registrada em {@link ConversationHandoff}. */
public enum HandoffKind {
    /** Assistente (ou sistema) transfere para humanos, com motivo e resumo. */
    ESCALATION,
    /** Operador assume a conversa (da fila, sem dono ou do assistente). */
    TAKE_OVER,
    /** Supervisor atribui a um operador. */
    ASSIGNMENT,
    /** Operador devolve à fila. */
    RELEASE,
    /** Operador devolve ao assistente. */
    RETURN_TO_ASSISTANT,
    CLOSE,
    REOPEN,
    /** Alinhamento com mudança de dono feita diretamente no caso (collection). */
    CASE_SYNC
}
