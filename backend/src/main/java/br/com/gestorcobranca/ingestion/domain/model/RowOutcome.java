package br.com.gestorcobranca.ingestion.domain.model;

/**
 * Resultado da gravação de um devedor ou título por uma linha. {@link #SKIPPED} (só para o título):
 * a linha é válida, mas a carga decidiu não gravá-la (ex.: título informado como pago pelo credor).
 */
public enum RowOutcome {
    CREATED,
    UPDATED,
    UNCHANGED,
    SKIPPED
}
