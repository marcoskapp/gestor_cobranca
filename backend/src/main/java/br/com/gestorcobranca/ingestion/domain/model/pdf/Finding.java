package br.com.gestorcobranca.ingestion.domain.model.pdf;

/**
 * Resultado de uma verificação automática da extração (nunca leva o valor lido, que pode ser dado pessoal).
 *
 * @param severity ERROR, WARNING ou INFO
 * @param code     código estável ({@code pdf.*} das verificações do PDF; {@code row.*} do validador do ingestion)
 * @param record   título (ordinal) a que se refere, ou nulo
 * @param field    campo canônico, ou nulo
 * @param blocking impede a aprovação até o mapa ser corrigido ou a divergência justificada
 */
public record Finding(Severity severity, String code, String message, Integer record, String field, SourceRef source,
                      boolean blocking) {

    public enum Severity { ERROR, WARNING, INFO }

    public static Finding blocking(String code, String message, Integer record, String field, SourceRef source) {
        return new Finding(Severity.ERROR, code, message, record, field, source, true);
    }

    public static Finding error(String code, String message, Integer record, String field, SourceRef source) {
        return new Finding(Severity.ERROR, code, message, record, field, source, false);
    }

    public static Finding warning(String code, String message, Integer record, String field, SourceRef source) {
        return new Finding(Severity.WARNING, code, message, record, field, source, false);
    }

    public static Finding info(String code, String message) {
        return new Finding(Severity.INFO, code, message, null, null, null, false);
    }
}
