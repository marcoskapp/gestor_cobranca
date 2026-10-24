package br.com.gestorcobranca.settlement.domain.model;

/** Ciclo de uma versão de regras de comissão: rascunho editável, publicada (imutável) ou rascunho descartado. */
public enum RuleSetStatus {
    DRAFT,
    PUBLISHED,
    DISCARDED
}
