package br.com.gestorcobranca.assistant.domain.model;

/** Avaliação humana de um turno amostrado. */
public enum ReviewVerdict {
    ADEQUATE,
    NEEDS_IMPROVEMENT,
    /** condição, promessa ou conduta fora da política (deve ser zero para avançar de fase) */
    OUT_OF_POLICY
}
