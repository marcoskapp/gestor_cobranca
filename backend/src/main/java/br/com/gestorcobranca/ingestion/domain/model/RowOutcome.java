package br.com.gestorcobranca.ingestion.domain.model;

/** Resultado da gravação de um devedor ou título por uma linha. */
public enum RowOutcome {
    CREATED,
    UPDATED,
    UNCHANGED
}
