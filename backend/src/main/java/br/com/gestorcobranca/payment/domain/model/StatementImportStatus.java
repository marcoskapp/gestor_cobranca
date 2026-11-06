package br.com.gestorcobranca.payment.domain.model;

/**
 * Situação da importação de extrato. {@code PROCESSED}, {@code FAILED} e {@code CANCELLED} são terminais;
 * {@code FAILED} e {@code CANCELLED} aceitam nova tentativa, que volta a {@code RECEIVED}.
 */
public enum StatementImportStatus {
    RECEIVED, PROCESSING, PROCESSED, FAILED,
    /** Processamento interrompido por uma pessoa: o que já foi gravado fica valendo. */
    CANCELLED
}
