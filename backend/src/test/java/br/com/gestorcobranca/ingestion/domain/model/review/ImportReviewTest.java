package br.com.gestorcobranca.ingestion.domain.model.review;

import br.com.gestorcobranca.ingestion.domain.model.ImportBatchStatus;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportReviewTest {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final ImportReview.Limits LIMITS = new ImportReview.Limits(10, true);
    private static final ImportReview.OpenAlerts NONE = new ImportReview.OpenAlerts(0, 0);
    private static final ImportReview.PreviewState VALIDATED = new ImportReview.PreviewState(ImportBatchStatus.VALIDATED, 10);

    private final UUID uploader = UUID.randomUUID();
    private final UUID preview = UUID.randomUUID();
    private ImportReview review;

    @BeforeEach
    void setUp() {
        review = new ImportReview(UUID.randomUUID(), UUID.randomUUID());
        ReflectionTestUtils.setField(review, "createdBy", uploader);
        review.attachPreview(preview);
    }

    private void ready() {
        review.confirmFormat(preview, uploader, NOW);
        review.totalsChecked(preview, true, NOW);
        review.analysisQueued(preview);
        review.analysisFinished(preview, 3, false, "{}", NOW);
    }

    @Test
    void blockersFollowTheSteps() {
        assertThat(review.blockers(new ImportReview.PreviewState(ImportBatchStatus.AWAITING_FORMAT, 0), NONE, LIMITS))
                .containsExactly(ReviewBlocker.FORMAT_PENDING);
        assertThat(review.blockers(new ImportReview.PreviewState(ImportBatchStatus.VALIDATING, 0), NONE, LIMITS))
                .containsExactly(ReviewBlocker.FORMAT_NOT_CONFIRMED, ReviewBlocker.VALIDATION_RUNNING);
        assertThat(review.blockers(new ImportReview.PreviewState(ImportBatchStatus.FAILED, 0), NONE, LIMITS))
                .containsExactly(ReviewBlocker.VALIDATION_FAILED);
        review.confirmFormat(preview, uploader, NOW);
        assertThat(review.blockers(VALIDATED, NONE, LIMITS))
                .containsExactly(ReviewBlocker.TOTALS_NOT_CHECKED, ReviewBlocker.ANALYSIS_RUNNING);
        review.totalsChecked(preview, false, NOW);
        assertThat(review.blockers(VALIDATED, NONE, LIMITS)).contains(ReviewBlocker.TOTALS_MISMATCH);
        review.totalsChecked(preview, true, NOW);
        assertThat(review.getTotalsChecks()).isEqualTo(2);
        assertThat(review.getTotalsMismatches()).isEqualTo(1);
        review.analysisQueued(preview);
        review.analysisFinished(preview, 30, false, "{}", NOW);
        assertThat(review.blockers(VALIDATED, new ImportReview.OpenAlerts(11, 0), LIMITS))
                .containsExactly(ReviewBlocker.TOO_MANY_OPEN_ALERTS);
        assertThat(review.blockers(VALIDATED, new ImportReview.OpenAlerts(10, 0), LIMITS)).isEmpty();
        assertThat(review.blockers(VALIDATED, new ImportReview.OpenAlerts(1, 1), LIMITS))
                .containsExactly(ReviewBlocker.SCALE_ALERTS_OPEN);
        assertThat(review.blockers(VALIDATED, new ImportReview.OpenAlerts(1, 1), new ImportReview.Limits(10, false))).isEmpty();
        assertThat(review.blockers(new ImportReview.PreviewState(ImportBatchStatus.VALIDATED, 0), NONE, LIMITS))
                .containsExactly(ReviewBlocker.NO_ROWS_TO_IMPORT);
    }

    @Test
    void newPreviewInvalidatesPreviousConfirmations() {
        ready();
        UUID second = UUID.randomUUID();
        review.attachPreview(second);
        assertThat(review.getPreviews()).isEqualTo(2);
        assertThat(review.isFormatConfirmed()).isFalse();
        assertThat(review.isTotalsMatched()).isFalse();
        assertThat(review.getAnalysisStatus()).isEqualTo(AnalysisStatus.NOT_STARTED);
        // análise da prévia antiga que termina depois é descartada
        assertThat(review.analysisFinished(preview, 5, false, "{}", NOW)).isFalse();
        assertThat(review.analysisQueued(preview)).isFalse();
        assertThatThrownBy(() -> review.confirmFormat(preview, uploader, NOW)).isInstanceOf(ConflictException.class);
    }

    @Test
    void commitBelowThresholdProceedsAndAboveWaitsForSecondPerson() {
        ready();
        UUID requester = UUID.randomUUID();
        boolean proceed = review.requestCommit(requester, NOW, List.of(), "{}", new BigDecimal("999.99"),
                new BigDecimal("1000.00"), null);
        assertThat(proceed).isTrue();
        assertThat(review.isApprovalRequired()).isFalse();
        review.imported(UUID.randomUUID());
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.COMMITTED);
        assertThatThrownBy(() -> review.moveTo(2)).isInstanceOf(ConflictException.class);
    }

    @Test
    void approvalIsSegregated() {
        ready();
        UUID requester = UUID.randomUUID();
        assertThat(review.requestCommit(requester, NOW, List.of(), "{}", new BigDecimal("1000.00"),
                new BigDecimal("1000.00"), "Credor corrigiu saldos")).isFalse();
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.AWAITING_APPROVAL);
        assertThat(review.getForceReason()).isEqualTo("Credor corrigiu saldos");
        assertThatThrownBy(() -> review.moveTo(3)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("aguardando aprovação");
        assertThatThrownBy(() -> review.imported(UUID.randomUUID())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> review.approve(requester, NOW, null)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "import_review.self_approval");
        assertThatThrownBy(() -> review.approve(uploader, NOW, null)).isInstanceOf(ConflictException.class);
        UUID approver = UUID.randomUUID();
        review.approve(approver, NOW, "Conferido com o credor");
        review.imported(UUID.randomUUID());
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.COMMITTED);
        assertThat(review.getDecidedBy()).isEqualTo(approver);
    }

    @Test
    void rejectionNeedsReasonAndSecondPerson() {
        ready();
        UUID requester = UUID.randomUUID();
        review.requestCommit(requester, NOW, List.of(), "{}", new BigDecimal("5000"), new BigDecimal("1000"), null);
        assertThatThrownBy(() -> review.reject(UUID.randomUUID(), NOW, " ")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> review.reject(requester, NOW, "não")).isInstanceOf(ConflictException.class);
        review.reject(UUID.randomUUID(), NOW, "Valores do credor desatualizados");
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.REJECTED);
    }

    @Test
    void commitWithBlockersIsRefusedAndNoThresholdMeansNoApproval() {
        assertThatThrownBy(() -> review.requestCommit(uploader, NOW, List.of(ReviewBlocker.TOTALS_MISMATCH), "{}",
                BigDecimal.TEN, null, null)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("totais");
        ready();
        assertThat(review.requestCommit(uploader, NOW, List.of(), "{}", new BigDecimal("9999999"), null, null)).isTrue();
    }

    @Test
    void cancelAndStepValidation() {
        assertThatThrownBy(() -> review.moveTo(7)).isInstanceOf(BusinessRuleException.class);
        review.moveTo(4);
        assertThat(review.getCurrentStep()).isEqualTo(4);
        assertThatThrownBy(() -> review.cancel(uploader, NOW, "")).isInstanceOf(BusinessRuleException.class);
        review.cancel(uploader, NOW, "Arquivo errado");
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.CANCELLED);
        assertThatThrownBy(() -> review.cancel(uploader, NOW, "de novo")).isInstanceOf(ConflictException.class);
    }

    @Test
    void alertDecisionsRequireJustificationAndFileAlertsAreNotExcludable() {
        UUID reviewId = UUID.randomUUID();
        ImportReviewAlert row = new ImportReviewAlert(reviewId, preview, "T-1", new AlertFinding(5, AlertType.DECIMAL_SCALE,
                100, "valor_principal", new BigDecimal("40000"), null, null, ReferenceKind.FILE, 50L, -2, "detalhe"));
        assertThatThrownBy(() -> row.decide(AlertDecision.CONFIRM, uploader, NOW, "ok")).isInstanceOf(BusinessRuleException.class);
        row.decide(AlertDecision.EXCLUDE, uploader, NOW, "Valor errado na origem");
        assertThat(row.getStatus()).isEqualTo(AlertStatus.EXCLUDED);
        assertThat(row.getJustification()).isEqualTo("Valor errado na origem");
        row.decide(AlertDecision.REOPEN, uploader, NOW, null);
        assertThat(row.getStatus()).isEqualTo(AlertStatus.OPEN);
        assertThat(row.getDecidedBy()).isNull();

        ImportReviewAlert file = new ImportReviewAlert(reviewId, preview, null, new AlertFinding(0, AlertType.CENTS_PATTERN,
                90, "valor_principal", null, null, null, null, 100L, null, "detalhe"));
        assertThatThrownBy(() -> file.decide(AlertDecision.EXCLUDE, uploader, NOW, "justificativa"))
                .isInstanceOf(BusinessRuleException.class);
        file.decide(AlertDecision.CONFIRM, uploader, NOW, "O credor envia valores inteiros");
        assertThat(file.getStatus()).isEqualTo(AlertStatus.CONFIRMED);
    }
}
