package br.com.gestorcobranca.collection.domain.model;

/** Origem de uma mudança de dono do atendimento. */
public enum AssignmentMode {
    /** Atribuição manual por supervisor. */
    MANUAL,
    /** Operador puxou o próximo caso da fila. */
    NEXT_CASE,
    /** Distribuição em lote da fila (rodízio ou menor carga). */
    DISTRIBUTION,
    /** Redistribuição da carteira de um operador. */
    REDISTRIBUTION,
    /** Caso devolvido à fila. */
    RELEASE,
    /** Operador registrou interação num caso sem dono e o assumiu. */
    INTERACTION,
    /** Atendimento entregue ao assistente de IA. */
    ASSISTANT_HANDOFF,
    /** Operador assumiu um caso que estava com o assistente ou com a fila. */
    TAKE_OVER,
    /** Abertura do caso (primeiro período, sem dono ou na fila). */
    OPENED,
    /** Mudança de fila pelo roteamento (critérios da fila ou do caso mudaram). */
    ROUTING,
    /** Encerramento do caso (último período, sem dono). */
    CLOSED
}
