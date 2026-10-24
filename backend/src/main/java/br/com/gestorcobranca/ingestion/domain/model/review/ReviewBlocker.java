package br.com.gestorcobranca.ingestion.domain.model.review;

/** Motivo que impede a efetivação da conferência. */
public enum ReviewBlocker {
    FORMAT_PENDING("Decida como ler os valores com separador ambíguo ou misturado."),
    FORMAT_NOT_CONFIRMED("Confirme o formato dos valores."),
    VALIDATION_RUNNING("Aguarde a validação das linhas."),
    VALIDATION_FAILED("A validação do arquivo falhou: corrija o arquivo e envie de novo."),
    NO_ROWS_TO_IMPORT("Nenhuma linha válida para importar."),
    TOTALS_NOT_CHECKED("Confira os totais com a sua planilha."),
    TOTALS_MISMATCH("Os totais informados não batem com o arquivo."),
    ANALYSIS_RUNNING("Aguarde a análise dos valores."),
    ANALYSIS_FAILED("A análise dos valores falhou: tente de novo em instantes."),
    ALERTS_TRUNCATED("Alertas demais: o arquivo parece ter um problema sistemático; revise o formato."),
    SCALE_ALERTS_OPEN("Revise todos os alertas de provável separador decimal."),
    TOO_MANY_OPEN_ALERTS("Revise os alertas de valor (há mais abertos do que o permitido).");

    private final String message;

    ReviewBlocker(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }
}
