package br.com.gestorcobranca.legal.domain.model;

/** Natureza do prazo jurídico. */
public enum DeadlineKind {
    /** Manifestação, réplica, impugnação. */
    RESPONSE,
    APPEAL,
    /** Pagamento/depósito (custas, preparo, cumprimento voluntário). */
    PAYMENT,
    /** Protocolo de petição (inicial, acordo para homologação). */
    FILING,
    HEARING_PREPARATION,
    /** Prazo de medida extrajudicial (purga da mora, intimação em cartório). */
    EXTRAJUDICIAL,
    /** Baixa de protesto/negativação após o pagamento (Súmula 548/STJ). */
    RESTRICTION_REMOVAL,
    OTHER
}
