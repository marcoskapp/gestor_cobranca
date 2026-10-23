package br.com.gestorcobranca.communication.domain.model;

/**
 * Dono do atendimento da conversa, com a mesma semântica do dono do caso no collection
 * (docs/tecnico/desenho/comunicacao.md §2, collection.md §3.4).
 */
public enum OwnerType {
    /** O assistente de IA responde conforme a política do assistente. */
    ASSISTANT,
    /** Aguardando um humano (fila por equipe/perfil exigido). */
    QUEUE,
    /** Atendida por um operador. */
    USER,
    /** Sem atendimento ativo (ex.: apenas notificações). */
    NONE
}
