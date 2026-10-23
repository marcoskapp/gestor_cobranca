package br.com.gestorcobranca.compliance.domain.model;

/** Quem originou a restrição. */
public enum RestrictionSource {
    OPERATOR,
    DEBTOR,
    /** Palavra de descadastro recebida pelo provedor do canal. */
    PROVIDER_STOP_KEYWORD,
    CREDITOR,
    LEGAL
}
