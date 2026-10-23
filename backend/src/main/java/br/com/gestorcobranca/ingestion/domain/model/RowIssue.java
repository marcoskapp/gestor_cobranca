package br.com.gestorcobranca.ingestion.domain.model;

import br.com.gestorcobranca.ingestion.domain.service.IssueValueMasker;

/**
 * Ocorrência de validação ou gravação de uma linha. O trecho do valor é mascarado na criação:
 * nenhuma ocorrência carrega documento, telefone ou e-mail em claro.
 *
 * @param rowNumber linha do arquivo; 0 para problemas do arquivo como um todo
 * @param field     coluna canônica envolvida (nulo quando não se aplica)
 * @param code      código estável (ex.: {@code row.due_date_invalid})
 */
public record RowIssue(long rowNumber, IssueSeverity severity, String field, String code, String message,
                       String valueExcerpt) {

    public static RowIssue error(long rowNumber, CanonicalField field, String code, String message, String rawValue) {
        return of(rowNumber, IssueSeverity.ERROR, field, code, message, rawValue);
    }

    public static RowIssue warning(long rowNumber, CanonicalField field, String code, String message, String rawValue) {
        return of(rowNumber, IssueSeverity.WARNING, field, code, message, rawValue);
    }

    /** Ocorrência sem valor associado (ex.: aviso devolvido pelo módulo debt). */
    public static RowIssue of(long rowNumber, IssueSeverity severity, String field, String code, String message) {
        return new RowIssue(rowNumber, severity, field, code, message, null);
    }

    private static RowIssue of(long rowNumber, IssueSeverity severity, CanonicalField field, String code,
                               String message, String rawValue) {
        return new RowIssue(rowNumber, severity, field == null ? null : field.column(), code, message,
                IssueValueMasker.excerpt(field, rawValue));
    }

    public boolean isError() {
        return severity == IssueSeverity.ERROR;
    }
}
