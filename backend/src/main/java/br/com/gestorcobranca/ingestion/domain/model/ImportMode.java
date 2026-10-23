package br.com.gestorcobranca.ingestion.domain.model;

/**
 * {@link #VALIDATE_ONLY}: pré-visualização — valida e reporta sem gravar no módulo debt.
 * {@link #IMPORT}: valida e grava devedores e títulos.
 */
public enum ImportMode {
    VALIDATE_ONLY(ImportBatchStatus.VALIDATING),
    IMPORT(ImportBatchStatus.PROCESSING);

    private final ImportBatchStatus workingStatus;

    ImportMode(ImportBatchStatus workingStatus) {
        this.workingStatus = workingStatus;
    }

    /** Status do lote enquanto o processamento está em andamento. */
    public ImportBatchStatus workingStatus() {
        return workingStatus;
    }
}
