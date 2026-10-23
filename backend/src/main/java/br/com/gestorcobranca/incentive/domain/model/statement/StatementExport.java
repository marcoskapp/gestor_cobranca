package br.com.gestorcobranca.incentive.domain.model.statement;

import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/** Exportação de extratos aprovados para a folha: arquivo guardado com hash (evidência). */
@Entity
@Table(name = "statement_export", schema = "incentive")
public class StatementExport extends TenantScopedEntity {

    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, updatable = false, length = 10)
    private ExportFormat format;

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

    @Column(name = "statements_count", nullable = false, updatable = false)
    private int statementsCount;

    @Column(name = "total_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money totalAmount;

    @Column(name = "exported_by", nullable = false, updatable = false)
    private UUID exportedBy;

    protected StatementExport() {
    }

    public StatementExport(LocalDate periodStart, ExportFormat format, String fileKey, String fileName, long fileSize,
                           String sha256, int statementsCount, Money totalAmount, UUID exportedBy) {
        this.periodStart = periodStart;
        this.format = format;
        this.fileKey = fileKey;
        this.fileName = fileName;
        this.contentType = format.contentType();
        this.fileSize = fileSize;
        this.sha256 = sha256;
        this.statementsCount = statementsCount;
        this.totalAmount = totalAmount;
        this.exportedBy = exportedBy;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public ExportFormat getFormat() {
        return format;
    }

    public String getFileKey() {
        return fileKey;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getSha256() {
        return sha256;
    }

    public int getStatementsCount() {
        return statementsCount;
    }

    public Money getTotalAmount() {
        return totalAmount;
    }

    public UUID getExportedBy() {
        return exportedBy;
    }
}
