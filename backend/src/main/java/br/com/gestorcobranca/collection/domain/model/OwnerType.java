package br.com.gestorcobranca.collection.domain.model;

/**
 * Dono do atendimento do caso, com a mesma semântica das conversas do módulo communication
 * (docs/design/comunicacao.md §2).
 */
public enum OwnerType {
    /** O assistente de IA conduz o atendimento. */
    ASSISTANT,
    /** Aguardando um humano: o caso está numa fila ou equipe, sem operador. */
    QUEUE,
    /** Atendido por um operador. */
    USER,
    /** Sem atendimento ativo (fora de fila, encerrado). */
    NONE
}
