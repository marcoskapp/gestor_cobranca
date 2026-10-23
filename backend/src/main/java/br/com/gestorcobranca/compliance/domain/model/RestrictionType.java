package br.com.gestorcobranca.compliance.domain.model;

/** Natureza da restrição de contato. */
public enum RestrictionType {
    /** Descadastro pedido pelo titular do contato (ex.: palavra "SAIR"). */
    OPT_OUT,
    /** Pedido do devedor para não ser contatado (inclui oposição LGPD). */
    DEBTOR_REQUEST,
    /** Bloqueio por decisão judicial ou orientação jurídica. */
    LEGAL_BLOCK,
    /** Pedido do credor para suspender o contato. */
    CREDITOR_REQUEST,
    /** Devedor falecido. */
    DECEASED,
    /** Suspeita de fraude (ex.: dívida contestada por uso indevido de documento). */
    FRAUD_SUSPECTED,
    /** Histórico de contato inválido (número/e-mail que não pertence ao devedor). */
    INVALID_CONTACT_HISTORY
}
