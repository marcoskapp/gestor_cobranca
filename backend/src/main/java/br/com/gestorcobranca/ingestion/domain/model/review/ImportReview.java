package br.com.gestorcobranca.ingestion.domain.model.review;

import br.com.gestorcobranca.ingestion.domain.model.ImportBatchStatus;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Conferência de uma importação (assistente em passos, {@code docs/tecnico/desenho/importacao.md}): acompanha o lote
 * de prévia corrente e registra o que o usuário confirmou — formato numérico, totais de controle, revisão dos alertas
 * — até a efetivação, que cria o lote de importação. Uma conferência pode ter várias prévias do mesmo arquivo (mudar o
 * formato depois da validação cria outra); confirmações valem só para a prévia em que foram feitas.
 * <p>
 * Segregação: a aprovação de segunda pessoa não pode ser de quem enviou o arquivo nem de quem pediu a efetivação.
 */
@Entity
@Table(name = "import_review", schema = "ingestion")
public class ImportReview extends TenantScopedEntity {

    public static final int FIRST_STEP = 1;
    public static final int LAST_STEP = 6;
    private static final int TEXT_MAX = 500;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", nullable = false, updatable = false)
    private UUID portfolioId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReviewStatus status;

    @Column(name = "current_step", nullable = false)
    private int currentStep;

    @Column(name = "current_batch_id", nullable = false)
    private UUID currentBatchId;

    @Column(name = "previews", nullable = false)
    private int previews;

    @Column(name = "format_confirmed_batch_id")
    private UUID formatConfirmedBatchId;

    @Column(name = "format_confirmed_at")
    private Instant formatConfirmedAt;

    @Column(name = "format_confirmed_by")
    private UUID formatConfirmedBy;

    @Column(name = "totals_matched_batch_id")
    private UUID totalsMatchedBatchId;

    @Column(name = "totals_last_batch_id")
    private UUID totalsLastBatchId;

    @Column(name = "totals_checked_at")
    private Instant totalsCheckedAt;

    @Column(name = "totals_checks", nullable = false)
    private int totalsChecks;

    @Column(name = "totals_mismatches", nullable = false)
    private int totalsMismatches;

    @Column(name = "analysis_batch_id")
    private UUID analysisBatchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false, length = 20)
    private AnalysisStatus analysisStatus;

    @Column(name = "analysis_finished_at")
    private Instant analysisFinishedAt;

    @Column(name = "alerts_total", nullable = false)
    private int alertsTotal;

    @Column(name = "alerts_truncated", nullable = false)
    private boolean alertsTruncated;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "analysis_summary")
    private String analysisSummary;

    @Column(name = "commit_requested_by")
    private UUID commitRequestedBy;

    @Column(name = "commit_requested_at")
    private Instant commitRequestedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "impact")
    private String impact;

    @Column(name = "approval_required", nullable = false)
    private boolean approvalRequired;

    @Column(name = "approval_threshold", precision = 19, scale = 2)
    private BigDecimal approvalThreshold;

    @Column(name = "force_reason", length = TEXT_MAX)
    private String forceReason;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", length = TEXT_MAX)
    private String decisionNote;

    @Column(name = "import_batch_id")
    private UUID importBatchId;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancel_reason", length = TEXT_MAX)
    private String cancelReason;

    protected ImportReview() {
    }

    public ImportReview(UUID creditorId, UUID portfolioId) {
        Guard.require(creditorId != null && portfolioId != null, "import_review.portfolio_required",
                "Credor e carteira obrigatórios");
        this.creditorId = creditorId;
        this.portfolioId = portfolioId;
        this.status = ReviewStatus.OPEN;
        this.currentStep = FIRST_STEP;
        this.analysisStatus = AnalysisStatus.NOT_STARTED;
    }

    /**
     * Passa a acompanhar uma prévia (a primeira, ou uma nova do mesmo arquivo com outro formato). Confirmações de
     * prévias anteriores deixam de valer.
     */
    public void attachPreview(UUID batchId) {
        requireOpen();
        Guard.require(batchId != null, "import_review.batch_required", "Prévia obrigatória");
        if (batchId.equals(currentBatchId)) {
            return;
        }
        this.currentBatchId = batchId;
        this.previews++;
        this.formatConfirmedBatchId = null;
        this.formatConfirmedAt = null;
        this.formatConfirmedBy = null;
        this.totalsMatchedBatchId = null;
        this.analysisBatchId = null;
        this.analysisStatus = AnalysisStatus.NOT_STARTED;
        this.analysisFinishedAt = null;
        this.alertsTotal = 0;
        this.alertsTruncated = false;
        this.analysisSummary = null;
    }

    public void moveTo(int step) {
        requireOpen();
        Guard.require(step >= FIRST_STEP && step <= LAST_STEP, "import_review.step_invalid", "Passo inválido");
        this.currentStep = step;
    }

    public void confirmFormat(UUID batchId, UUID actor, Instant now) {
        requireOpen();
        requireCurrent(batchId);
        this.formatConfirmedBatchId = batchId;
        this.formatConfirmedAt = now;
        this.formatConfirmedBy = actor;
    }

    /** Registra uma conferência de totais na prévia corrente. */
    public void totalsChecked(UUID batchId, boolean matched, Instant now) {
        requireOpen();
        requireCurrent(batchId);
        this.totalsChecks++;
        this.totalsCheckedAt = now;
        this.totalsLastBatchId = batchId;
        if (matched) {
            this.totalsMatchedBatchId = batchId;
        } else {
            this.totalsMismatches++;
            this.totalsMatchedBatchId = null;
        }
    }

    /** A prévia terminou de validar e a análise de valores foi enfileirada. Ignorado se a prévia não é a corrente. */
    public boolean analysisQueued(UUID batchId) {
        if (status != ReviewStatus.OPEN || !batchId.equals(currentBatchId)) {
            return false;
        }
        this.analysisBatchId = batchId;
        this.analysisStatus = AnalysisStatus.PENDING;
        return true;
    }

    /** Ignorado se a prévia deixou de ser a corrente (o usuário mudou o formato enquanto a análise rodava). */
    public boolean analysisFinished(UUID batchId, int alerts, boolean truncated, String summaryJson, Instant now) {
        if (status != ReviewStatus.OPEN || !batchId.equals(currentBatchId) || !batchId.equals(analysisBatchId)) {
            return false;
        }
        this.analysisStatus = AnalysisStatus.DONE;
        this.alertsTotal = alerts;
        this.alertsTruncated = truncated;
        this.analysisSummary = summaryJson;
        this.analysisFinishedAt = now;
        return true;
    }

    public void analysisFailed(UUID batchId) {
        if (status == ReviewStatus.OPEN && batchId.equals(analysisBatchId) && batchId.equals(currentBatchId)) {
            this.analysisStatus = AnalysisStatus.FAILED;
        }
    }

    /**
     * Motivos que impedem a efetivação, na ordem dos passos.
     *
     * @param batch situação da prévia corrente
     * @param open  alertas abertos da prévia corrente (total e de escala)
     */
    public List<ReviewBlocker> blockers(PreviewState batch, OpenAlerts open, Limits limits) {
        List<ReviewBlocker> blockers = new ArrayList<>();
        if (batch.status() == ImportBatchStatus.AWAITING_FORMAT) {
            blockers.add(ReviewBlocker.FORMAT_PENDING);
            return blockers;
        }
        if (batch.status() == ImportBatchStatus.FAILED || batch.status() == ImportBatchStatus.CANCELLED) {
            blockers.add(ReviewBlocker.VALIDATION_FAILED);
            return blockers;
        }
        if (!currentBatchId.equals(formatConfirmedBatchId)) {
            blockers.add(ReviewBlocker.FORMAT_NOT_CONFIRMED);
        }
        if (batch.status() != ImportBatchStatus.VALIDATED) {
            blockers.add(ReviewBlocker.VALIDATION_RUNNING);
            return blockers;
        }
        if (batch.rowsToImport() <= 0) {
            blockers.add(ReviewBlocker.NO_ROWS_TO_IMPORT);
        }
        if (!currentBatchId.equals(totalsLastBatchId)) {
            blockers.add(ReviewBlocker.TOTALS_NOT_CHECKED);
        } else if (!currentBatchId.equals(totalsMatchedBatchId)) {
            blockers.add(ReviewBlocker.TOTALS_MISMATCH);
        }
        switch (analysisStatus) {
            case NOT_STARTED, PENDING -> blockers.add(ReviewBlocker.ANALYSIS_RUNNING);
            case FAILED -> blockers.add(ReviewBlocker.ANALYSIS_FAILED);
            case DONE -> {
                if (alertsTruncated) {
                    blockers.add(ReviewBlocker.ALERTS_TRUNCATED);
                }
                if (limits.requireScaleReview() && open.scale() > 0) {
                    blockers.add(ReviewBlocker.SCALE_ALERTS_OPEN);
                }
                if (open.total() > limits.maxUnreviewedAlerts()) {
                    blockers.add(ReviewBlocker.TOO_MANY_OPEN_ALERTS);
                }
            }
        }
        return blockers;
    }

    /**
     * Pedido de efetivação. Sem bloqueios; acima do limite fica aguardando a segunda pessoa.
     *
     * @return verdadeiro se a efetivação pode seguir de imediato (sem aprovação)
     */
    public boolean requestCommit(UUID actor, Instant now, List<ReviewBlocker> blockers, String impactJson,
                                 BigDecimal amount, BigDecimal threshold, String forceReason) {
        requireOpen();
        Guard.requireState(blockers.isEmpty(), "import_review.blocked",
                blockers.isEmpty() ? "" : blockers.getFirst().message());
        this.commitRequestedBy = actor;
        this.commitRequestedAt = now;
        this.impact = impactJson;
        this.forceReason = forceReason == null || forceReason.isBlank() ? null : truncate(forceReason.strip());
        this.approvalThreshold = threshold;
        this.approvalRequired = threshold != null && threshold.signum() > 0 && amount.compareTo(threshold) >= 0;
        if (approvalRequired) {
            this.status = ReviewStatus.AWAITING_APPROVAL;
            return false;
        }
        return true;
    }

    /** Aprovação de segunda pessoa (nunca quem enviou o arquivo nem quem pediu a efetivação). */
    public void approve(UUID actor, Instant now, String note) {
        requireAwaitingApproval();
        requireSecondPerson(actor);
        this.decidedBy = actor;
        this.decidedAt = now;
        this.decisionNote = note == null || note.isBlank() ? null : truncate(note.strip());
    }

    public void reject(UUID actor, Instant now, String reason) {
        requireAwaitingApproval();
        requireSecondPerson(actor);
        Guard.require(reason != null && !reason.isBlank(), "import_review.reject_reason_required",
                "Informe o motivo da recusa");
        this.decidedBy = actor;
        this.decidedAt = now;
        this.decisionNote = truncate(reason.strip());
        this.status = ReviewStatus.REJECTED;
    }

    /** Lote de importação criado: conferência encerrada. */
    public void imported(UUID batchId) {
        Guard.requireState(status == ReviewStatus.OPEN && commitRequestedAt != null && !approvalRequired
                        || status == ReviewStatus.AWAITING_APPROVAL && decidedBy != null,
                "import_review.not_committable", "Conferência não está pronta para efetivar");
        this.importBatchId = batchId;
        this.status = ReviewStatus.COMMITTED;
    }

    public void cancel(UUID actor, Instant now, String reason) {
        Guard.requireState(status == ReviewStatus.OPEN || status == ReviewStatus.AWAITING_APPROVAL,
                "import_review.not_cancellable", "Conferência já encerrada");
        Guard.require(reason != null && !reason.isBlank(), "import_review.cancel_reason_required",
                "Informe o motivo do cancelamento");
        this.status = ReviewStatus.CANCELLED;
        this.cancelledAt = now;
        this.cancelledBy = actor;
        this.cancelReason = truncate(reason.strip());
    }

    private void requireSecondPerson(UUID actor) {
        Guard.requireState(actor != null && !actor.equals(commitRequestedBy) && !actor.equals(getCreatedBy()),
                "import_review.self_approval",
                "A aprovação deve ser feita por outra pessoa (nem quem enviou o arquivo nem quem pediu a efetivação)");
    }

    private void requireAwaitingApproval() {
        Guard.requireState(status == ReviewStatus.AWAITING_APPROVAL, "import_review.not_awaiting_approval",
                "Conferência não está aguardando aprovação");
    }

    public void requireOpen() {
        Guard.requireState(status == ReviewStatus.OPEN, "import_review.not_open",
                status == ReviewStatus.AWAITING_APPROVAL ? "Conferência aguardando aprovação: nada pode ser alterado"
                        : "Conferência encerrada");
    }

    private void requireCurrent(UUID batchId) {
        Guard.requireState(Objects.equals(batchId, currentBatchId), "import_review.preview_outdated",
                "A prévia mudou: atualize a tela");
    }

    private static String truncate(String value) {
        return value.length() <= TEXT_MAX ? value : value.substring(0, TEXT_MAX);
    }

    /**
     * Situação da prévia corrente para o cálculo dos bloqueios.
     *
     * @param rowsToImport linhas válidas menos as excluídas na conferência
     */
    public record PreviewState(ImportBatchStatus status, long rowsToImport) {
    }

    /** Alertas abertos da prévia corrente. */
    public record OpenAlerts(long total, long scale) {
    }

    /** Limites da revisão de alertas ({@code app.ingestion.review.*}). */
    public record Limits(int maxUnreviewedAlerts, boolean requireScaleReview) {
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public int getCurrentStep() {
        return currentStep;
    }

    public UUID getCurrentBatchId() {
        return currentBatchId;
    }

    public int getPreviews() {
        return previews;
    }

    public UUID getFormatConfirmedBatchId() {
        return formatConfirmedBatchId;
    }

    public Instant getFormatConfirmedAt() {
        return formatConfirmedAt;
    }

    public UUID getFormatConfirmedBy() {
        return formatConfirmedBy;
    }

    public boolean isFormatConfirmed() {
        return currentBatchId != null && currentBatchId.equals(formatConfirmedBatchId);
    }

    public UUID getTotalsMatchedBatchId() {
        return totalsMatchedBatchId;
    }

    public boolean isTotalsMatched() {
        return currentBatchId != null && currentBatchId.equals(totalsMatchedBatchId);
    }

    /** A última conferência de totais foi feita na prévia corrente. */
    public boolean isTotalsCheckedOnCurrent() {
        return currentBatchId != null && currentBatchId.equals(totalsLastBatchId);
    }

    public Instant getTotalsCheckedAt() {
        return totalsCheckedAt;
    }

    public int getTotalsChecks() {
        return totalsChecks;
    }

    public int getTotalsMismatches() {
        return totalsMismatches;
    }

    public UUID getAnalysisBatchId() {
        return analysisBatchId;
    }

    public AnalysisStatus getAnalysisStatus() {
        return analysisStatus;
    }

    public Instant getAnalysisFinishedAt() {
        return analysisFinishedAt;
    }

    public int getAlertsTotal() {
        return alertsTotal;
    }

    public boolean isAlertsTruncated() {
        return alertsTruncated;
    }

    public String getAnalysisSummary() {
        return analysisSummary;
    }

    public UUID getCommitRequestedBy() {
        return commitRequestedBy;
    }

    public Instant getCommitRequestedAt() {
        return commitRequestedAt;
    }

    public String getImpact() {
        return impact;
    }

    public boolean isApprovalRequired() {
        return approvalRequired;
    }

    public BigDecimal getApprovalThreshold() {
        return approvalThreshold;
    }

    public String getForceReason() {
        return forceReason;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public UUID getImportBatchId() {
        return importBatchId;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public UUID getCancelledBy() {
        return cancelledBy;
    }

    public String getCancelReason() {
        return cancelReason;
    }
}
