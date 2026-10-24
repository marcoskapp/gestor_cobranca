package br.com.gestorcobranca.assistant.domain.model;

/** Quem detectou a necessidade de transferência ou o bloqueio. */
public enum TriggerSource {
    /** regra determinística (palavras-chave e padrões), independente do modelo */
    RULE,
    /** sinal ou pedido do próprio modelo (ferramentas {@code register_intent}/{@code transfer_to_human}) */
    MODEL,
    /** guardrail de saída recusou a resposta */
    GUARDRAIL,
    /** limite operacional (turnos, custo, tokens, tentativas de identidade) */
    LIMIT,
    /** motor de negociação (proposta acima da alçada, formalização com aprovação humana) */
    ENGINE,
    /** política do assistente (desligado, canal ou modo incompatível) */
    POLICY,
    /** falha técnica (provedor indisponível, tempo esgotado, erro inesperado) */
    FAILURE
}
