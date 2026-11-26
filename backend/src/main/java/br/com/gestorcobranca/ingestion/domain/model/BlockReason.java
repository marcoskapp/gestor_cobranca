package br.com.gestorcobranca.ingestion.domain.model;

/** Por que o lote foi bloqueado (sem reprocessar, sem nova conferência, sem baixar o arquivo, sem reenvio). */
public enum BlockReason {
    /** O arquivo tem linha de titular anonimizado (LGPD; SEG-20261124-D01h). */
    SUBJECT_ANONYMIZED
}
