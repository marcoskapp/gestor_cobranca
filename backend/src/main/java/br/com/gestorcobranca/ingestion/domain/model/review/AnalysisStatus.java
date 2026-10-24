package br.com.gestorcobranca.ingestion.domain.model.review;

/** Análise de valores (alertas) da prévia corrente. */
public enum AnalysisStatus {
    /** A prévia ainda não terminou de validar. */
    NOT_STARTED,
    PENDING,
    DONE,
    FAILED
}
