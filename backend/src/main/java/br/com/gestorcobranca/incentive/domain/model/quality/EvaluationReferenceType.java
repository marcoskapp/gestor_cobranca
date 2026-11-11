package br.com.gestorcobranca.incentive.domain.model.quality;

/**
 * O que foi monitorado (por id; o conteúdo fica no módulo de origem). {@code INTERACTION} é a interação do histórico do
 * caso; {@code CASE} fica para avaliações antigas e para o caso como um todo.
 */
public enum EvaluationReferenceType {
    CALL, CONVERSATION, CASE, AGREEMENT, INTERACTION, OTHER
}
