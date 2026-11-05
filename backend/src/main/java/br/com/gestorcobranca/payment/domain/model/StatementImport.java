package br.com.gestorcobranca.payment.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Importação de extrato bancário ou arquivo de retorno (F15). O arquivo fica no {@code FileStorage}
 * ({@code PAYMENT_RETURN}) com SHA-256; o mesmo arquivo não é importado duas vezes. Processamento
 * assíncrono (job) em lotes com retomada (decisão 868), com contagens apuradas sobre as linhas gravadas.
 */
@Entity
@Table(name = "statement_import", schema = "payment")
public class StatementImport extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 20, updatable = false)
    private StatementFormat format;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StatementImportStatus status;

    @Column(name = "creditor_id", updatable = false)
    private UUID creditorId;

    @Column(name = "file_key", nullable = false, length = 500, updatable = false)
    private String fileKey;

    @Column(name = "file_name", nullable = false, length = 255, updatable = false)
    private String fileName;

    @Column(name = "file_size", nullable = false, updatable = false)
    private long fileSize;

    @Column(name = "file_sha256", nullable = false, length = 64, updatable = false)
    private String fileSha256;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "total_lines", nullable = false)
    private int totalLines;

    @Column(name = "reconciled_lines", nullable = false)
    private int reconciledLines;

    @Column(name = "created_payments", nullable = false)
    private int createdPayments;

    @Column(name = "divergent_lines", nullable = false)
    private int divergentLines;

    @Column(name = "ignored_lines", nullable = false)
    private int ignoredLines;

    @Column(name = "failure_code", length = 60)
    private String failureCode;

    protected StatementImport() {
    }

    public StatementImport(StatementFormat format, UUID creditorId, String fileKey, String fileName, long fileSize,
                           String fileSha256, UUID requestedBy) {
        Guard.require(format != null, "statement.format_required", "Formato obrigatório");
        Guard.require(fileKey != null && fileSha256 != null, "statement.file_required", "Arquivo obrigatório");
        Guard.require(requestedBy != null, "statement.requester_required", "Responsável obrigatório");
        this.format = format;
        this.status = StatementImportStatus.RECEIVED;
        this.creditorId = creditorId;
        this.fileKey = fileKey;
        this.fileName = fileName == null || fileName.isBlank() ? "extrato" : fileName.length() > 255
                ? fileName.substring(0, 255) : fileName;
        this.fileSize = fileSize;
        this.fileSha256 = fileSha256;
        this.requestedBy = requestedBy;
    }

    /**
     * Inicia o processamento ou o retoma depois de uma tentativa interrompida (o processamento grava as linhas em
     * lotes, cada um na própria transação, e a retomada pula as linhas já gravadas).
     *
     * @return {@code false} se a importação já terminou (processada ou com falha)
     */
    public boolean startOrResume(Instant now) {
        if (status == StatementImportStatus.PROCESSING) {
            return true;
        }
        if (status != StatementImportStatus.RECEIVED) {
            return false;
        }
        this.status = StatementImportStatus.PROCESSING;
        this.startedAt = now;
        return true;
    }

    public boolean isProcessing() {
        return status == StatementImportStatus.PROCESSING;
    }

    /** Conclui com as contagens apuradas sobre as linhas gravadas (a retomada não conta linha duas vezes). */
    public void finish(Map<StatementLineStatus, Long> lines, Instant now) {
        Guard.require(isProcessing(), "statement.not_processing", "Importação não está em processamento");
        tally(lines);
        this.status = StatementImportStatus.PROCESSED;
        this.finishedAt = now;
    }

    /**
     * Falha depois de parte das linhas gravadas (tentativas esgotadas): as linhas e baixas já confirmadas ficam,
     * e as contagens refletem só elas (decisão 873).
     */
    public void failProcessing(String code, Map<StatementLineStatus, Long> lines, Instant now) {
        Guard.require(isProcessing(), "statement.not_processing", "Importação não está em processamento");
        tally(lines);
        fail(code, now);
    }

    private void tally(Map<StatementLineStatus, Long> lines) {
        totalLines = 0;
        reconciledLines = 0;
        createdPayments = 0;
        divergentLines = 0;
        ignoredLines = 0;
        lines.forEach((outcome, quantity) -> {
            int n = Math.toIntExact(quantity);
            totalLines += n;
            switch (outcome) {
                case RECONCILED -> reconciledLines += n;
                case CREATED_PAYMENT -> {
                    createdPayments += n;
                    reconciledLines += n;
                }
                case DIVERGENT -> divergentLines += n;
                case ALREADY_RECONCILED, IGNORED_DUPLICATE, IGNORED_DEBIT -> ignoredLines += n;
            }
        });
    }

    public void fail(String code, Instant now) {
        this.status = StatementImportStatus.FAILED;
        this.failureCode = code == null ? "FAILED" : code.length() > 60 ? code.substring(0, 60) : code;
        this.finishedAt = now;
    }

    public StatementFormat getFormat() {
        return format;
    }

    public StatementImportStatus getStatus() {
        return status;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public String getFileKey() {
        return fileKey;
    }

    public String getFileName() {
        return fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getFileSha256() {
        return fileSha256;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public int getTotalLines() {
        return totalLines;
    }

    public int getReconciledLines() {
        return reconciledLines;
    }

    public int getCreatedPayments() {
        return createdPayments;
    }

    public int getDivergentLines() {
        return divergentLines;
    }

    public int getIgnoredLines() {
        return ignoredLines;
    }

    public String getFailureCode() {
        return failureCode;
    }
}
