package br.com.gestorcobranca.ingestion.domain.model.pdf;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Importação PDF inteligente: o PDF recebido (guardado criptografado no armazenamento), o mapa de extração corrente
 * (versionado em {@link PdfMapRevision}), o resumo das verificações e as decisões do usuário até a aprovação, que gera
 * o lote canônico e abre a conferência do ingestion ({@code reviewId}). Nenhum valor extraído do PDF fica nesta
 * entidade em claro: os títulos são recalculados do arquivo pelo intérprete (determinístico) e, na leitura visual, ficam
 * cifrados ({@code visualResult}). O resumo guarda só contagens, somas e códigos.
 */
@Entity
@Table(name = "pdf_import", schema = "ingestion")
public class PdfImport extends TenantScopedEntity {

    public static final int TEXT_MAX = 500;
    public static final int JUSTIFICATION_MIN = 10;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", nullable = false, updatable = false)
    private UUID portfolioId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false, length = 20)
    private PdfImportChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PdfImportStatus status;

    @Column(name = "file_name", nullable = false, updatable = false, length = 255)
    private String fileName;

    @Column(name = "file_size", nullable = false, updatable = false)
    private long fileSize;

    @Column(name = "file_sha256", nullable = false, updatable = false, length = 64)
    private String fileSha256;

    @Column(name = "file_key", length = 500)
    private String fileKey;

    @Column(name = "file_deleted_at")
    private Instant fileDeletedAt;

    @Column(name = "page_count", nullable = false, updatable = false)
    private int pageCount;

    @Column(name = "text_layer", nullable = false, updatable = false)
    private boolean textLayer;

    @Column(name = "idempotency_key", updatable = false, length = 80)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "map")
    private String map;

    @Enumerated(EnumType.STRING)
    @Column(name = "map_origin", length = 20)
    private MapOrigin mapOrigin;

    @Column(name = "map_revision", nullable = false)
    private int mapRevision;

    @Column(name = "template_id")
    private UUID templateId;

    @Column(name = "fingerprint", length = 64)
    private String fingerprint;

    /** Resultado da leitura visual (títulos e totais lidos pelo modelo), cifrado: contém dados pessoais. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "visual_result")
    private String visualResult;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "decisions", nullable = false)
    private String decisions;

    @Column(name = "ai_calls", nullable = false)
    private int aiCalls;

    @Column(name = "ai_input_tokens", nullable = false)
    private long aiInputTokens;

    @Column(name = "ai_output_tokens", nullable = false)
    private long aiOutputTokens;

    @Column(name = "ai_cost", nullable = false, precision = 19, scale = 6)
    private BigDecimal aiCost;

    @Column(name = "ai_currency", length = 3)
    private String aiCurrency;

    @Column(name = "ai_model", length = 200)
    private String aiModel;

    @Column(name = "ai_latency_ms", nullable = false)
    private long aiLatencyMs;

    @Column(name = "failure_code", length = 80)
    private String failureCode;

    @Column(name = "failure_message", length = TEXT_MAX)
    private String failureMessage;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "totals_justification")
    private String totalsJustification;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "review_id")
    private UUID reviewId;

    @Column(name = "discarded_by")
    private UUID discardedBy;

    @Column(name = "discarded_at")
    private Instant discardedAt;

    @Column(name = "discard_reason", length = TEXT_MAX)
    private String discardReason;

    protected PdfImport() {
    }

    public PdfImport(UUID creditorId, UUID portfolioId, PdfImportChannel channel, String fileName, long fileSize,
                     String fileSha256, String fileKey, int pageCount, boolean textLayer, String idempotencyKey) {
        this.creditorId = Objects.requireNonNull(creditorId);
        this.portfolioId = Objects.requireNonNull(portfolioId);
        this.channel = Objects.requireNonNull(channel);
        this.fileName = Guard.requireNonNull(fileName, "pdf_import.file_name_required", "Nome do arquivo obrigatório");
        this.fileSize = fileSize;
        this.fileSha256 = Objects.requireNonNull(fileSha256);
        this.fileKey = Objects.requireNonNull(fileKey);
        this.pageCount = pageCount;
        this.textLayer = textLayer;
        this.idempotencyKey = idempotencyKey;
        this.status = PdfImportStatus.RECEIVED;
        this.decisions = "{}";
        this.aiCost = BigDecimal.ZERO;
    }

    // ------------------------------------------------------------------ transições

    public void startMapping() {
        Guard.requireState(status == PdfImportStatus.RECEIVED || status == PdfImportStatus.MAPPING
                || status == PdfImportStatus.AWAITING_MAP || status == PdfImportStatus.AWAITING_VALIDATION,
                "pdf_import.not_mappable", "A importação não aceita novo mapa neste estado");
        status = PdfImportStatus.MAPPING;
        failureCode = null;
        failureMessage = null;
    }

    /**
     * Novo mapa (da IA, do mapa salvo, do usuário ou da leitura visual): incrementa a revisão, limpa as decisões sobre
     * os títulos (a numeração pode mudar) e guarda o resumo das verificações.
     */
    public void applyMap(String mapJson, MapOrigin origin, String fingerprint, UUID templateId, String summaryJson) {
        Guard.requireState(status == PdfImportStatus.MAPPING || status.editable(), "pdf_import.not_editable",
                "A importação não aceita alterações neste estado");
        this.map = Objects.requireNonNull(mapJson);
        this.mapOrigin = Objects.requireNonNull(origin);
        this.fingerprint = fingerprint;
        this.templateId = templateId;
        this.summary = summaryJson;
        this.mapRevision++;
        this.decisions = "{}";
        this.totalsJustification = null;
        this.status = PdfImportStatus.AWAITING_VALIDATION;
    }

    public void recordVisualResult(String encryptedJson) {
        this.visualResult = encryptedJson;
    }

    /** Sem mapa (IA desligada, indisponível ou proposta inválida): o usuário escreve o mapa na tela. */
    public void awaitMap(String code, String message) {
        Guard.requireState(status == PdfImportStatus.MAPPING, "pdf_import.not_mapping", "A importação não está em mapeamento");
        status = PdfImportStatus.AWAITING_MAP;
        failureCode = code;
        failureMessage = truncate(message);
    }

    public void fail(String code, String message) {
        Guard.requireState(status.open(), "pdf_import.closed", "A importação já foi encerrada");
        status = PdfImportStatus.FAILED;
        failureCode = code;
        failureMessage = truncate(message);
    }

    public void updateSummary(String summaryJson) {
        this.summary = summaryJson;
    }

    public void updateDecisions(String decisionsJson) {
        Guard.requireState(status == PdfImportStatus.AWAITING_VALIDATION, "pdf_import.not_editable",
                "A importação não aceita alterações neste estado");
        this.decisions = Objects.requireNonNull(decisionsJson);
    }

    public void addUsage(long inputTokens, long outputTokens, BigDecimal cost, String currency, String model,
                         long latencyMs) {
        aiCalls++;
        aiInputTokens += Math.max(0, inputTokens);
        aiOutputTokens += Math.max(0, outputTokens);
        aiCost = aiCost.add(cost == null ? BigDecimal.ZERO : cost);
        aiCurrency = currency == null ? aiCurrency : currency;
        aiModel = model == null ? aiModel : model;
        aiLatencyMs += Math.max(0, latencyMs);
    }

    /**
     * @param justification obrigatória quando os totais do documento não conferem
     */
    public void approve(UUID actor, Instant now, UUID reviewId, boolean totalsMatch, String justification) {
        Guard.requireState(status == PdfImportStatus.AWAITING_VALIDATION, "pdf_import.not_awaiting_validation",
                "Só uma importação aguardando validação pode ser aprovada");
        if (!totalsMatch) {
            Guard.require(justification != null && justification.strip().length() >= JUSTIFICATION_MIN,
                    "pdf_import.totals_justification_required",
                    "Os totais do documento não conferem: corrija o mapa ou justifique (mínimo " + JUSTIFICATION_MIN
                            + " caracteres)");
            Guard.require(justification.length() <= TEXT_MAX, "pdf_import.text_too_long", "Justificativa longa demais");
            this.totalsJustification = justification.strip();
        }
        this.status = PdfImportStatus.APPROVED;
        this.approvedBy = Objects.requireNonNull(actor);
        this.approvedAt = Objects.requireNonNull(now);
        this.reviewId = Objects.requireNonNull(reviewId);
    }

    public void discard(UUID actor, Instant now, String reason) {
        Guard.requireState(status.open(), "pdf_import.closed", "A importação já foi encerrada");
        Guard.require(reason != null && !reason.isBlank(), "pdf_import.reason_required", "Informe o motivo do descarte");
        Guard.require(reason.length() <= TEXT_MAX, "pdf_import.text_too_long", "Motivo longo demais");
        status = PdfImportStatus.DISCARDED;
        discardedBy = actor;
        discardedAt = now;
        discardReason = reason.strip();
    }

    /** Retenção: o arquivo foi apagado do armazenamento (o hash permanece como evidência). */
    public void fileDeleted(Instant now) {
        Guard.requireState(!status.open(), "pdf_import.open", "A importação ainda está aberta");
        fileKey = null;
        fileDeletedAt = now;
        visualResult = null;
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= TEXT_MAX ? message : message.substring(0, TEXT_MAX);
    }

    // ------------------------------------------------------------------ leitura

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public PdfImportChannel getChannel() {
        return channel;
    }

    public PdfImportStatus getStatus() {
        return status;
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

    public String getFileKey() {
        return fileKey;
    }

    public Instant getFileDeletedAt() {
        return fileDeletedAt;
    }

    public int getPageCount() {
        return pageCount;
    }

    public boolean hasTextLayer() {
        return textLayer;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getMap() {
        return map;
    }

    public MapOrigin getMapOrigin() {
        return mapOrigin;
    }

    public int getMapRevision() {
        return mapRevision;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getVisualResult() {
        return visualResult;
    }

    public String getSummary() {
        return summary;
    }

    public String getDecisions() {
        return decisions;
    }

    public int getAiCalls() {
        return aiCalls;
    }

    public long getAiInputTokens() {
        return aiInputTokens;
    }

    public long getAiOutputTokens() {
        return aiOutputTokens;
    }

    public BigDecimal getAiCost() {
        return aiCost;
    }

    public String getAiCurrency() {
        return aiCurrency;
    }

    public String getAiModel() {
        return aiModel;
    }

    public long getAiLatencyMs() {
        return aiLatencyMs;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public String getTotalsJustification() {
        return totalsJustification;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public UUID getDiscardedBy() {
        return discardedBy;
    }

    public Instant getDiscardedAt() {
        return discardedAt;
    }

    public String getDiscardReason() {
        return discardReason;
    }
}
