package br.com.gestorcobranca.ingestion.domain.model.review;

/** Decisão do usuário sobre alertas. */
public enum AlertDecision {
    CONFIRM,
    EXCLUDE,
    /** Desfaz a decisão (volta a aberto), até a efetivação. */
    REOPEN
}
