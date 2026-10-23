package br.com.gestorcobranca.ingestion.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Lote de importação (5.2): origem, credor, carteira, layout e versão, arquivo original
 * (chave, nome, tamanho e SHA-256), modo, contadores, checkpoint e datas. O solicitante é
 * {@code createdBy}.
 * <p>
 * {@code lastProcessedRow} é o checkpoint do processamento retomável: linhas até ele já foram
 * contabilizadas (e, na importação, gravadas) e não são relidas numa retomada.
 * {@code totalRows} é apurado na leitura prévia do arquivo (antes de qualquer gravação) e permite
 * acompanhar o progresso.
 * <p>
 * Cancelamento: um lote ainda na fila é cancelado de imediato; em processamento, o pedido é
 * registrado e o processamento para ao fim do bloco corrente — o que já foi gravado permanece
 * (cada linha é idempotente e pode ser completada por uma nova carga).
 */
@Entity
@Table(name = "import_batch", schema = "ingestion")
public class ImportBatch extends TenantScopedEntity {

    private static final int REASON_MAX = 500;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", nullable = false, updatable = false)
    private UUID portfolioId;

    @Column(name = "layout_id", updatable = false)
    private UUID layoutId;

    @Column(name = "layout_code", nullable = false, length = 40, updatable = false)
    private String layoutCode;

    @Column(name = "layout_version", nullable = false, updatable = false)
    private int layoutVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 10, updatable = false)
    private ImportSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20, updatable = false)
    private ImportMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ImportBatchStatus status;

    @Column(name = "status_reason", length = REASON_MAX)
    private String statusReason;

    @Column(name = "file_key", nullable = false, length = 300, updatable = false)
    private String fileKey;

    @Column(name = "file_name", nullable = false, length = 200, updatable = false)
    private String fileName;

    @Column(name = "file_content_type", length = 120, updatable = false)
    private String fileContentType;

    @Column(name = "file_size", nullable = false, updatable = false)
    private long fileSize;

    @Column(name = "file_sha256", nullable = false, length = 64, updatable = false)
    private String fileSha256;

    @Column(name = "forced", nullable = false, updatable = false)
    private boolean forced;

    @Column(name = "force_reason", length = REASON_MAX, updatable = false)
    private String forceReason;

    @Column(name = "duplicate_of_batch_id", updatable = false)
    private UUID duplicateOfBatchId;

    @Column(name = "reprocess_of_batch_id", updatable = false)
    private UUID reprocessOfBatchId;

    @Column(name = "rows_read", nullable = false)
    private int rowsRead;

    @Column(name = "rows_valid", nullable = false)
    private int rowsValid;

    @Column(name = "rows_with_error", nullable = false)
    private int rowsWithError;

    @Column(name = "errors", nullable = false)
    private int errors;

    @Column(name = "warnings", nullable = false)
    private int warnings;

    @Column(name = "debtors_created", nullable = false)
    private int debtorsCreated;

    @Column(name = "debtors_updated", nullable = false)
    private int debtorsUpdated;

    @Column(name = "titles_created", nullable = false)
    private int titlesCreated;

    @Column(name = "titles_updated", nullable = false)
    private int titlesUpdated;

    @Column(name = "titles_unchanged", nullable = false)
    private int titlesUnchanged;

    @Column(name = "titles_skipped", nullable = false)
    private int titlesSkipped;

    @Column(name = "titles_suspended", nullable = false)
    private int titlesSuspended;

    @Column(name = "total_rows")
    private Long totalRows;

    @Column(name = "cancel_requested_at")
    private Instant cancelRequestedAt;

    @Column(name = "cancel_requested_by")
    private UUID cancelRequestedBy;

    @Column(name = "principal_total", nullable = false, precision = 19, scale = 2)
    private Money principalTotal;

    @Column(name = "issues_recorded", nullable = false)
    private int issuesRecorded;

    @Column(name = "last_processed_row", nullable = false)
    private long lastProcessedRow;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected ImportBatch() {
    }

    public ImportBatch(UUID creditorId, UUID portfolioId, FileLayout layout, ImportSource source, ImportMode mode,
                       StoredContent file, Forcing forcing, UUID reprocessOfBatchId) {
        Guard.require(creditorId != null, "import_batch.creditor_required", "Credor obrigatório");
        Guard.require(portfolioId != null, "import_batch.portfolio_required", "Carteira obrigatória");
        Guard.require(layout != null && source != null && mode != null && file != null,
                "import_batch.definition_required", "Layout, origem, modo e arquivo são obrigatórios");
        Guard.require(layout.format() == source.format(), "import_batch.format_mismatch",
                "Formato do layout não corresponde à origem do lote");
        Guard.require(file.sha256() != null && file.sha256().matches("[0-9a-f]{64}"), "import_batch.sha256_invalid",
                "SHA-256 do arquivo inválido");
        this.creditorId = creditorId;
        this.portfolioId = portfolioId;
        this.layoutId = layout.layoutId();
        this.layoutCode = layout.code();
        this.layoutVersion = layout.version();
        this.source = source;
        this.mode = mode;
        this.status = ImportBatchStatus.RECEIVED;
        this.fileKey = file.key();
        this.fileName = file.name();
        this.fileContentType = file.contentType();
        this.fileSize = file.size();
        this.fileSha256 = file.sha256();
        if (forcing != null) {
            Guard.require(forcing.reason() != null && !forcing.reason().isBlank(), "import.force_reason_required",
                    "Informe o motivo do reprocessamento de um arquivo já importado");
            this.forced = true;
            this.forceReason = truncate(forcing.reason().strip());
            this.duplicateOfBatchId = forcing.duplicateOfBatchId();
        }
        this.reprocessOfBatchId = reprocessOfBatchId;
        this.principalTotal = Money.ZERO;
    }

    /** Lote aguardando o início do processamento (a leitura prévia ainda não ocorreu). */
    public boolean isAwaitingStart() {
        return status == ImportBatchStatus.RECEIVED;
    }

    /** Em validação ou importação. */
    public boolean isInProgress() {
        return status == mode.workingStatus();
    }

    /**
     * Inicia o processamento após a leitura prévia do arquivo.
     *
     * @param totalRows linhas de dados encontradas na leitura prévia
     */
    public void begin(Instant now, long totalRows) {
        Guard.requireState(status == ImportBatchStatus.RECEIVED, "import_batch.not_processable",
                "Lote com status %s não pode ser iniciado".formatted(status));
        Guard.require(totalRows >= 0, "import_batch.total_rows_invalid", "Total de linhas inválido");
        this.status = mode.workingStatus();
        this.startedAt = now;
        this.totalRows = totalRows;
    }

    /**
     * Contabiliza um bloco confirmado e avança o checkpoint.
     *
     * @param expectedCheckpoint checkpoint de onde o bloco partiu: se outro processamento já o
     *                           avançou (execução duplicada após perda de lease), o bloco é recusado
     */
    public void recordBlock(BlockTally tally, long expectedCheckpoint, long lastRow, int newIssuesRecorded) {
        requireWorking();
        Guard.requireState(expectedCheckpoint == lastProcessedRow, "import_batch.checkpoint_conflict",
                "Lote avançado por outro processamento");
        Guard.requireState(lastRow >= lastProcessedRow, "import_batch.checkpoint_regression",
                "Checkpoint não pode retroceder");
        rowsRead += tally.rowsRead();
        rowsValid += tally.rowsValid();
        rowsWithError += tally.rowsWithError();
        errors += tally.errors();
        warnings += tally.warnings();
        titlesCreated += tally.titlesCreated();
        titlesUpdated += tally.titlesUpdated();
        titlesUnchanged += tally.titlesUnchanged();
        titlesSkipped += tally.titlesSkipped();
        titlesSuspended += tally.titlesSuspended();
        principalTotal = principalTotal.plus(tally.principalTotal());
        issuesRecorded += newIssuesRecorded;
        lastProcessedRow = lastRow;
    }

    /**
     * Ajusta o total de ocorrências gravadas ao valor contado no banco. Usado na retomada: linhas
     * gravadas antes da queda já registraram seus avisos, mas o bloco não chegou a ser contabilizado.
     */
    public void reconcileIssuesRecorded(int persisted) {
        requireWorking();
        this.issuesRecorded = persisted;
    }

    /**
     * Encerra o processamento: COMPLETED / COMPLETED_WITH_ERRORS (importação), VALIDATED
     * (pré-visualização) ou FAILED se o arquivo não tinha nenhuma linha.
     */
    public ImportBatchStatus complete(Instant now, int debtorsCreated, int debtorsUpdated) {
        requireWorking();
        if (rowsRead == 0) {
            finish(ImportBatchStatus.FAILED, "Arquivo sem linhas de dados", now);
            return status;
        }
        this.debtorsCreated = debtorsCreated;
        this.debtorsUpdated = debtorsUpdated;
        ImportBatchStatus target = switch (mode) {
            case VALIDATE_ONLY -> ImportBatchStatus.VALIDATED;
            case IMPORT -> rowsWithError > 0 ? ImportBatchStatus.COMPLETED_WITH_ERRORS : ImportBatchStatus.COMPLETED;
        };
        finish(target, null, now);
        return status;
    }

    /** Ocorrência de erro do arquivo como um todo (linha 0) gravada antes de falhar o lote. */
    public void recordFileError() {
        Guard.requireState(status == ImportBatchStatus.RECEIVED || status == mode.workingStatus(),
                "import_batch.not_failable", "Lote com status %s já foi encerrado".formatted(status));
        errors++;
        issuesRecorded++;
    }

    /** Falha do lote (arquivo ilegível, carteira sem aceitar cargas, erro persistente). */
    public void fail(Instant now, String reason, int debtorsCreated, int debtorsUpdated) {
        Guard.requireState(status == ImportBatchStatus.RECEIVED || status == mode.workingStatus(),
                "import_batch.not_failable", "Lote com status %s já foi encerrado".formatted(status));
        this.debtorsCreated = debtorsCreated;
        this.debtorsUpdated = debtorsUpdated;
        if (startedAt == null) {
            startedAt = now;
        }
        finish(ImportBatchStatus.FAILED, reason, now);
    }

    /**
     * Pedido de cancelamento.
     *
     * @return verdadeiro se o lote foi cancelado de imediato (ainda na fila); falso se o pedido foi
     *         registrado e o processamento em andamento vai parar ao fim do bloco corrente
     */
    public boolean requestCancel(Instant now, UUID requestedBy, String reason) {
        Guard.require(reason != null && !reason.isBlank(), "import_batch.cancel_reason_required",
                "Informe o motivo do cancelamento");
        if (status == ImportBatchStatus.RECEIVED) {
            cancelRequestedAt = now;
            cancelRequestedBy = requestedBy;
            finish(ImportBatchStatus.CANCELLED, reason.strip(), now);
            return true;
        }
        Guard.requireState(status == mode.workingStatus(), "import_batch.not_cancellable",
                "Lote com status %s já foi encerrado".formatted(status));
        Guard.requireState(cancelRequestedAt == null, "import_batch.cancel_already_requested",
                "Cancelamento já solicitado; o processamento será interrompido ao fim do bloco corrente");
        cancelRequestedAt = now;
        cancelRequestedBy = requestedBy;
        statusReason = truncate(reason.strip());
        return false;
    }

    public boolean isCancelRequested() {
        return cancelRequestedAt != null;
    }

    /** Interrompe o processamento a pedido do usuário; contadores e checkpoint ficam como estão. */
    public void stopCancelled(Instant now, int debtorsCreated, int debtorsUpdated) {
        requireWorking();
        Guard.requireState(cancelRequestedAt != null, "import_batch.cancel_not_requested", "Cancelamento não solicitado");
        this.debtorsCreated = debtorsCreated;
        this.debtorsUpdated = debtorsUpdated;
        finish(ImportBatchStatus.CANCELLED, statusReason, now);
    }

    /** Percentual de linhas processadas (nulo antes da leitura prévia). */
    public Integer progressPercent() {
        if (totalRows == null) {
            return null;
        }
        if (status.isTerminal() && status != ImportBatchStatus.CANCELLED && status != ImportBatchStatus.FAILED) {
            return 100;
        }
        return totalRows == 0 ? 0 : (int) Math.min(100, rowsRead * 100L / totalRows);
    }

    private void finish(ImportBatchStatus target, String reason, Instant now) {
        this.status = target;
        this.statusReason = reason == null ? null : truncate(reason);
        this.finishedAt = now;
    }

    private void requireWorking() {
        Guard.requireState(status == mode.workingStatus(), "import_batch.not_in_progress",
                "Lote com status %s não está em processamento".formatted(status));
    }

    private static String truncate(String value) {
        return value.length() <= REASON_MAX ? value : value.substring(0, REASON_MAX);
    }

    /** Ocorrências geradas além do limite por lote não foram gravadas. */
    public boolean isIssuesTruncated() {
        return errors + warnings > issuesRecorded;
    }

    public boolean writesToDebt() {
        return mode == ImportMode.IMPORT;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public UUID getLayoutId() {
        return layoutId;
    }

    public String getLayoutCode() {
        return layoutCode;
    }

    public int getLayoutVersion() {
        return layoutVersion;
    }

    public ImportSource getSource() {
        return source;
    }

    public ImportMode getMode() {
        return mode;
    }

    public ImportBatchStatus getStatus() {
        return status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public String getFileKey() {
        return fileKey;
    }

    public String getFileName() {
        return fileName;
    }

    public String getFileContentType() {
        return fileContentType;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getFileSha256() {
        return fileSha256;
    }

    public boolean isForced() {
        return forced;
    }

    public String getForceReason() {
        return forceReason;
    }

    public UUID getDuplicateOfBatchId() {
        return duplicateOfBatchId;
    }

    public UUID getReprocessOfBatchId() {
        return reprocessOfBatchId;
    }

    public int getRowsRead() {
        return rowsRead;
    }

    public int getRowsValid() {
        return rowsValid;
    }

    public int getRowsWithError() {
        return rowsWithError;
    }

    public int getErrors() {
        return errors;
    }

    public int getWarnings() {
        return warnings;
    }

    public int getDebtorsCreated() {
        return debtorsCreated;
    }

    public int getDebtorsUpdated() {
        return debtorsUpdated;
    }

    public int getTitlesCreated() {
        return titlesCreated;
    }

    public int getTitlesUpdated() {
        return titlesUpdated;
    }

    public int getTitlesUnchanged() {
        return titlesUnchanged;
    }

    public int getTitlesSkipped() {
        return titlesSkipped;
    }

    public int getTitlesSuspended() {
        return titlesSuspended;
    }

    public Long getTotalRows() {
        return totalRows;
    }

    public Instant getCancelRequestedAt() {
        return cancelRequestedAt;
    }

    public UUID getCancelRequestedBy() {
        return cancelRequestedBy;
    }

    public Money getPrincipalTotal() {
        return principalTotal;
    }

    public int getIssuesRecorded() {
        return issuesRecorded;
    }

    public long getLastProcessedRow() {
        return lastProcessedRow;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    /** Arquivo original já gravado no armazenamento. */
    public record StoredContent(String key, String name, String contentType, long size, String sha256) {
    }

    /**
     * Confirmação explícita de reprocessamento de um arquivo já importado com sucesso.
     *
     * @param duplicateOfBatchId lote concluído com o mesmo conteúdo na carteira
     */
    public record Forcing(UUID duplicateOfBatchId, String reason) {
    }
}
