package br.com.gestorcobranca.settlement.domain.model;

import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/** Arquivo do demonstrativo enviado ao credor (CSV/XLSX conciliáveis, PDF resumo), guardado com SHA-256. Append-only. */
@Entity
@Table(name = "statement_export", schema = "settlement")
public class SettlementExport extends TenantScopedEntity {

    public enum Format {
        CSV("text/csv", "csv"),
        XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
        PDF("application/pdf", "pdf");

        private final String contentType;
        private final String extension;

        Format(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        public String contentType() {
            return contentType;
        }

        public String extension() {
            return extension;
        }
    }

    @Column(name = "statement_id", nullable = false, updatable = false)
    private UUID statementId;
    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, updatable = false, length = 10)
    private Format format;
    @Column(name = "file_key", nullable = false, updatable = false, length = 512)
    private String fileKey;
    @Column(name = "file_name", nullable = false, updatable = false, length = 200)
    private String fileName;
    @Column(name = "content_type", nullable = false, updatable = false, length = 100)
    private String contentType;
    @Column(name = "file_size", nullable = false, updatable = false)
    private long fileSize;
    @Column(name = "sha256", nullable = false, updatable = false, length = 64)
    private String sha256;
    @Column(name = "rows_count", nullable = false, updatable = false)
    private int rowsCount;
    @Column(name = "generated_by", nullable = false, updatable = false)
    private UUID generatedBy;

    protected SettlementExport() {
    }

    public SettlementExport(UUID statementId, Format format, String fileKey, String fileName, long fileSize, String sha256,
                           int rowsCount, UUID generatedBy) {
        this.statementId = statementId;
        this.format = format;
        this.fileKey = fileKey;
        this.fileName = fileName;
        this.contentType = format.contentType();
        this.fileSize = fileSize;
        this.sha256 = sha256;
        this.rowsCount = rowsCount;
        this.generatedBy = generatedBy;
    }

    public UUID getStatementId() { return statementId; }
    public Format getFormat() { return format; }
    public String getFileKey() { return fileKey; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getFileSize() { return fileSize; }
    public String getSha256() { return sha256; }
    public int getRowsCount() { return rowsCount; }
    public UUID getGeneratedBy() { return generatedBy; }
}
