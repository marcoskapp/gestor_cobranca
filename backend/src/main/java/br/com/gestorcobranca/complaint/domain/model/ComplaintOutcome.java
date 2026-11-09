package br.com.gestorcobranca.complaint.domain.model;

/** Resultado no encerramento. */
public enum ComplaintOutcome {
    /** Atendida (acordo, correção, baixa, fim dos contatos). */
    RESOLVED,
    /** Respondida sem acolher o pedido, com fundamentação. */
    NOT_ACCEPTED,
    /** Improcedente (não era da assessoria, devedor errado, duplicada). */
    UNFOUNDED,
    /** Encerrada pelo canal sem retorno do consumidor. */
    CLOSED_BY_CHANNEL
}
