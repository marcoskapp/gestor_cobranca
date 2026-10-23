package br.com.gestorcobranca.ingestion.domain.model;

import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/** Ocorrência gravada de um lote (append-only no banco). O trecho de valor já vem mascarado. */
@Entity
@Table(name = "import_row_issue", schema = "ingestion")
public class ImportRowIssue extends TenantScopedEntity {

    private static final int MESSAGE_MAX = 500;
    private static final int EXCERPT_MAX = 120;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Column(name = "row_number", nullable = false, updatable = false)
    private long rowNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10, updatable = false)
    private IssueSeverity severity;

    @Column(name = "field", length = 60, updatable = false)
    private String field;

    @Column(name = "code", nullable = false, length = 80, updatable = false)
    private String code;

    @Column(name = "message", nullable = false, length = MESSAGE_MAX, updatable = false)
    private String message;

    @Column(name = "value_excerpt", length = EXCERPT_MAX, updatable = false)
    private String valueExcerpt;

    protected ImportRowIssue() {
    }

    public ImportRowIssue(UUID batchId, RowIssue issue) {
        this.batchId = batchId;
        this.rowNumber = issue.rowNumber();
        this.severity = issue.severity();
        this.field = issue.field();
        this.code = issue.code();
        this.message = cut(issue.message(), MESSAGE_MAX);
        this.valueExcerpt = cut(issue.valueExcerpt(), EXCERPT_MAX);
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public UUID getBatchId() {
        return batchId;
    }

    public long getRowNumber() {
        return rowNumber;
    }

    public IssueSeverity getSeverity() {
        return severity;
    }

    public String getField() {
        return field;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getValueExcerpt() {
        return valueExcerpt;
    }
}
