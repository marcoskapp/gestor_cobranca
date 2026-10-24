package br.com.gestorcobranca.assistant.domain.model;

/** Por que o turno entrou na amostra de revisão. */
public enum SampleReason {
    /** amostragem aleatória (taxa da política) */
    RANDOM,
    /** guardrail acionado: sempre revisado */
    GUARDRAIL,
    /** falha técnica: sempre revisado */
    FAILURE
}
