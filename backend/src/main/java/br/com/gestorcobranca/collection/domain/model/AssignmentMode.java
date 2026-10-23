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
    /** Retorno vencido sem atendimento recolocou o caso na fila. */
    CALLBACK_RETURN,
    /** Atendimento entregue ao assistente de IA. */
    ASSISTANT_HANDOFF
}
