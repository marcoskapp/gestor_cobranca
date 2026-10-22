package br.com.gestorcobranca.debt.domain.model;

/** Origem do contato (rastreabilidade e base legal do uso — M09). */
public enum ContactSource {
    /** Carga do credor. */
    IMPORT,
    /** Cadastro manual por operador. */
    MANUAL,
    /** Informado pelo próprio devedor (atendimento, portal). */
    DEBTOR,
    /** Enriquecimento ou validação por provedor externo. */
    PROVIDER
}
