package br.com.gestorcobranca.legal.domain.model;

/** Motivo do encerramento do caso jurídico (etapa 9). */
public enum ClosureReason {
    /** Dívida paga (quitação, levantamento de valores). */
    PAID,
    /** Acordo (judicial ou extrajudicial) cumprido. */
    AGREEMENT_FULFILLED,
    /** Bem expropriado/adjudicado e valores apurados. */
    ASSET_RECOVERED,
    /** Baixa como incobrável (sem bens, devedor não localizado). */
    UNCOLLECTIBLE,
    /** Prescrição (inclusive intercorrente). */
    PRESCRIBED,
    /** Desistência ou extinção sem resolução. */
    WITHDRAWN,
    /** Pedido do credor. */
    CREDITOR_REQUEST,
    OTHER
}
