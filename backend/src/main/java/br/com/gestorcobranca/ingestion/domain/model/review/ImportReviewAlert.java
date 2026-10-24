package br.com.gestorcobranca.ingestion.domain.model.review;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Alerta de valor de uma linha da prévia (ou do arquivo, linha 0) e a decisão do usuário. Só a decisão muda depois de
 * criado (privilégio de coluna no banco).
 */
@Entity
@Table(name = "import_review_alert", schema = "ingestion")
public class ImportReviewAlert extends TenantScopedEntity {

    public static final int JUSTIFICATION_MIN = 5;
    private static final int TEXT_MAX = 500;

    @Column(name = "review_id", nullable = false, updatable = false)
    private UUID reviewId;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private UUID batchId;

    @Column(name = "row_number", nullable = false, updatable = false)
    private long rowNumber;

    @Column(name = "external_id", length = 80, updatable = false)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40, updatable = false)
    private AlertType type;

    @Column(name = "priority", nullable = false, updatable = false)
    private int priority;

    @Column(name = "field", length = 60, updatable = false)
    private String field;

    @Column(name = "amount", precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "expected_low", precision = 19, scale = 2, updatable = false)
    private BigDecimal expectedLow;

    @Column(name = "expected_high", precision = 19, scale = 2, updatable = false)
    private BigDecimal expectedHigh;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference", length = 30, updatable = false)
    private ReferenceKind reference;

    @Column(name = "reference_size", updatable = false)
    private Long referenceSize;

    @Column(name = "suggested_exponent", updatable = false)
    private Integer suggestedExponent;

    @Column(name = "detail", nullable = false, length = TEXT_MAX, updatable = false)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AlertStatus status;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "justification", length = TEXT_MAX)
    private String justification;

    protected ImportReviewAlert() {
    }

    public ImportReviewAlert(UUID reviewId, UUID batchId, String externalId, AlertFinding finding) {
        Guard.require(reviewId != null && batchId != null && finding != null && finding.rowNumber() >= 0,
                "import_review_alert.invalid", "Alerta inválido");
        this.reviewId = reviewId;
        this.batchId = batchId;
        this.rowNumber = finding.rowNumber();
        this.externalId = externalId;
        this.type = finding.type();
        this.priority = finding.priority();
        this.field = finding.field();
        this.amount = finding.amount();
        this.expectedLow = finding.expectedLow();
        this.expectedHigh = finding.expectedHigh();
        this.reference = finding.reference();
        this.referenceSize = finding.referenceSize();
        this.suggestedExponent = finding.suggestedExponent();
        String text = finding.detail();
        this.detail = text.length() <= TEXT_MAX ? text : text.substring(0, TEXT_MAX);
        this.status = AlertStatus.OPEN;
    }

    /** Decisão do usuário; confirmar ou excluir exigem justificativa. Alerta do arquivo (linha 0) não exclui linhas. */
    public void decide(AlertDecision decision, UUID actor, Instant now, String reason) {
        if (decision == AlertDecision.REOPEN) {
            status = AlertStatus.OPEN;
            decidedBy = null;
            decidedAt = null;
            justification = null;
            return;
        }
        Guard.require(reason != null && reason.strip().length() >= JUSTIFICATION_MIN, "import_review.justification_required",
                "Informe a justificativa (pelo menos %d caracteres)".formatted(JUSTIFICATION_MIN));
        Guard.require(decision != AlertDecision.EXCLUDE || rowNumber > 0, "import_review.file_alert_not_excludable",
                "Alerta do arquivo como um todo não exclui linhas: confirme ou volte ao formato");
        status = decision == AlertDecision.CONFIRM ? AlertStatus.CONFIRMED : AlertStatus.EXCLUDED;
        decidedBy = actor;
        decidedAt = now;
        String text = reason.strip();
        justification = text.length() <= TEXT_MAX ? text : text.substring(0, TEXT_MAX);
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public long getRowNumber() {
        return rowNumber;
    }

    public String getExternalId() {
        return externalId;
    }

    public AlertType getType() {
        return type;
    }

    public int getPriority() {
        return priority;
    }

    public String getField() {
        return field;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getExpectedLow() {
        return expectedLow;
    }

    public BigDecimal getExpectedHigh() {
        return expectedHigh;
    }

    public ReferenceKind getReference() {
        return reference;
    }

    public Long getReferenceSize() {
        return referenceSize;
    }

    public Integer getSuggestedExponent() {
        return suggestedExponent;
    }

    public String getDetail() {
        return detail;
    }

    public AlertStatus getStatus() {
        return status;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getJustification() {
        return justification;
    }
}
