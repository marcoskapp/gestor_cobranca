package br.com.gestorcobranca.ingestion.domain.model;

/** Formato físico do conteúdo de um lote. Layouts de credor são CSV ou XLSX; JSON é o da API. */
public enum ImportFormat {
    CSV,
    XLSX,
    JSON
}
