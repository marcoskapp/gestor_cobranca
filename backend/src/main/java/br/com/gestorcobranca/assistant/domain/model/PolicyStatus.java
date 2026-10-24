package br.com.gestorcobranca.assistant.domain.model;

/** Ciclo da política do assistente: rascunho editável → publicada (imutável) → aposentada. */
public enum PolicyStatus {
    DRAFT,
    PUBLISHED,
    RETIRED
}
