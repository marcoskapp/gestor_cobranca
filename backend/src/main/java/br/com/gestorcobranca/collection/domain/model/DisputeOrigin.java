package br.com.gestorcobranca.collection.domain.model;

/** Quem abriu a contestação. */
public enum DisputeOrigin {
    /** Registrada pelo operador a pedido do devedor. */
    OPERATOR,
    /** Aberta pelo próprio devedor (portal). */
    DEBTOR,
    /** Informada pelo credor. */
    CREDITOR,
    /** Recebida por órgão de defesa do consumidor / jurídico. */
    LEGAL
}
