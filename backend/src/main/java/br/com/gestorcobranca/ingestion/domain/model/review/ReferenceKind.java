package br.com.gestorcobranca.ingestion.domain.model.review;

/** Distribuição usada como referência do "ticket médio" de uma linha, na ordem de preferência. */
public enum ReferenceKind {
    HISTORY_PRODUCT,
    HISTORY_PORTFOLIO,
    HISTORY_CREDITOR,
    FILE_PRODUCT,
    FILE;

    public boolean history() {
        return this == HISTORY_PRODUCT || this == HISTORY_PORTFOLIO || this == HISTORY_CREDITOR;
    }
}
