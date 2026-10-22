package br.com.gestorcobranca.shared.storage;

/** Categoria do arquivo: define o prefixo no S3 e as regras de retenção. */
public enum FileCategory {
    IMPORT("imports"),
    EVIDENCE("evidences"),
    AGREEMENT_TERM("agreement-terms"),
    PAYMENT_RETURN("payment-returns"),
    REPORT_EXPORT("exports"),
    SETTLEMENT_STATEMENT("settlements"),
    CALL_RECORDING("call-recordings"),
    MESSAGE_ATTACHMENT("message-attachments"),
    DATA_SUBJECT_EXPORT("data-subject-exports");

    private final String prefix;

    FileCategory(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }
}
