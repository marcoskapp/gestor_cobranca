package br.com.gestorcobranca.collection.domain.model;

/**
 * Como a fila distribui casos. Em todas as estratégias o operador pode pedir o "próximo caso";
 * as estratégias de distribuição atribuem casos em lote quando o supervisor aciona a
 * distribuição.
 */
public enum DistributionStrategy {
    /** Somente sob demanda: o operador puxa o próximo caso. */
    PULL,
    /** Distribuição em rodízio entre os membros da fila. */
    ROUND_ROBIN,
    /** Distribuição para o membro com menos casos ativos na fila. */
    LEAST_LOADED,
    /**
     * Por nível de desempenho: casos de maior valor esperado primeiro aos níveis mais altos, capacidade por nível e
     * fatia de desenvolvimento (distribuicao-por-desempenho.md §4.2). Exige capacidade por operador na fila.
     */
    TIERED
}
