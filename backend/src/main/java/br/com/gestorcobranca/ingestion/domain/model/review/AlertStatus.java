package br.com.gestorcobranca.ingestion.domain.model.review;

/** Revisão de um alerta pelo usuário. */
public enum AlertStatus {
    OPEN,
    /** "Confirmo que está correto" (com justificativa). */
    CONFIRMED,
    /** A linha não será importada (com justificativa). */
    EXCLUDED
}
