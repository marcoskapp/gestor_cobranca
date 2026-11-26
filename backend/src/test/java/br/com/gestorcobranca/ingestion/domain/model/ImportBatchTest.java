package br.com.gestorcobranca.ingestion.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportBatchTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final String SHA = "a".repeat(64);

    private static ImportBatch batch(ImportMode mode) {
        return new ImportBatch(UUID.randomUUID(), UUID.randomUUID(), FileLayout.standard(ImportFormat.CSV),
                ImportSource.CSV, mode, new ImportBatch.StoredContent("tenants/x/imports/f.csv", "f.csv", "text/csv", 10, SHA),
                null, null);
    }

    private static BlockTally tally(int valid, int rejected) {
        BlockTally tally = new BlockTally();
        for (int i = 0; i < valid; i++) {
            tally.validRow(new BigDecimal("10.00"), 0, RowOutcome.CREATED, false);
        }
        for (int i = 0; i < rejected; i++) {
            tally.rejectedRow(2, 1);
        }
        return tally;
    }

    @Test
    void processesBlocksAndCompletes() {
        ImportBatch batch = batch(ImportMode.IMPORT);
        batch.begin(NOW, 5);
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.PROCESSING);
        batch.recordBlock(tally(3, 0), 0, 4, 0);
        assertThat(batch.progressPercent()).isEqualTo(60);
        batch.recordBlock(tally(1, 1), 4, 6, 3);
        assertThat(batch.complete(NOW.plusSeconds(5), 2, 1)).isEqualTo(ImportBatchStatus.COMPLETED_WITH_ERRORS);
        assertThat(batch.getRowsRead()).isEqualTo(5);
        assertThat(batch.getTitlesCreated()).isEqualTo(4);
        assertThat(batch.getPrincipalTotal().amount()).isEqualByComparingTo("40.00");
        assertThat(batch.getErrors()).isEqualTo(2);
        assertThat(batch.isIssuesTruncated()).isFalse();
        assertThat(batch.progressPercent()).isEqualTo(100);
    }

    @Test
    void refusesBlockFromAStaleCheckpoint() {
        ImportBatch batch = batch(ImportMode.IMPORT);
        batch.begin(NOW, 10);
        batch.recordBlock(tally(2, 0), 0, 2, 0);
        assertThatThrownBy(() -> batch.recordBlock(tally(2, 0), 0, 4, 0)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "import_batch.checkpoint_conflict");
    }

    @Test
    void previewEndsValidatedAndEmptyFileFails() {
        ImportBatch preview = batch(ImportMode.VALIDATE_ONLY);
        preview.begin(NOW, 1);
        assertThat(preview.getStatus()).isEqualTo(ImportBatchStatus.VALIDATING);
        BlockTally tally = new BlockTally();
        tally.validRow(BigDecimal.TEN, 0, null, false);
        preview.recordBlock(tally, 0, 2, 0);
        assertThat(preview.complete(NOW, 0, 0)).isEqualTo(ImportBatchStatus.VALIDATED);

        ImportBatch empty = batch(ImportMode.IMPORT);
        empty.begin(NOW, 0);
        assertThat(empty.complete(NOW, 0, 0)).isEqualTo(ImportBatchStatus.FAILED);
    }

    @Test
    void cancelsImmediatelyWhenQueuedAndAtTheNextBlockWhenRunning() {
        ImportBatch queued = batch(ImportMode.IMPORT);
        assertThat(queued.requestCancel(NOW, UUID.randomUUID(), "arquivo errado")).isTrue();
        assertThat(queued.getStatus()).isEqualTo(ImportBatchStatus.CANCELLED);

        ImportBatch running = batch(ImportMode.IMPORT);
        running.begin(NOW, 100);
        assertThat(running.requestCancel(NOW, UUID.randomUUID(), "arquivo errado")).isFalse();
        assertThat(running.getStatus()).isEqualTo(ImportBatchStatus.PROCESSING);
        assertThatThrownBy(() -> running.requestCancel(NOW, UUID.randomUUID(), "de novo"))
                .isInstanceOf(ConflictException.class);
        running.recordBlock(tally(1, 0), 0, 2, 0);
        running.stopCancelled(NOW, 1, 0);
        assertThat(running.getStatus()).isEqualTo(ImportBatchStatus.CANCELLED);
        assertThat(running.getStatusReason()).isEqualTo("arquivo errado");
        assertThatThrownBy(() -> running.requestCancel(NOW, UUID.randomUUID(), "x")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> batch(ImportMode.IMPORT).requestCancel(NOW, UUID.randomUUID(), " "))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void forcingRequiresReasonAndFormatMustMatchSource() {
        assertThatThrownBy(() -> new ImportBatch(UUID.randomUUID(), UUID.randomUUID(), FileLayout.standard(ImportFormat.CSV),
                ImportSource.CSV, ImportMode.IMPORT, new ImportBatch.StoredContent("k", "f.csv", null, 1, SHA),
                new ImportBatch.Forcing(UUID.randomUUID(), " "), null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new ImportBatch(UUID.randomUUID(), UUID.randomUUID(), FileLayout.standard(ImportFormat.XLSX),
                ImportSource.CSV, ImportMode.IMPORT, new ImportBatch.StoredContent("k", "f.csv", null, 1, SHA), null, null))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void fileErrorFailsBeforeStart() {
        ImportBatch batch = batch(ImportMode.IMPORT);
        batch.recordFileError();
        batch.fail(NOW, "CSV malformado", 0, 0);
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.FAILED);
        assertThat(batch.getErrors()).isEqualTo(1);
        assertThat(batch.progressPercent()).isNull();
    }

    @Test
    void blockCancelsAQueuedBatchAtOnceAndOnlyMarksAFinishedOne() {
        UUID system = br.com.gestorcobranca.shared.security.Actor.SYSTEM_ID;
        ImportBatch queued = batch(ImportMode.IMPORT);
        assertThat(queued.block(BlockReason.SUBJECT_ANONYMIZED, NOW, system)).isEqualTo(BlockEffect.CANCELLED_NOW);
        assertThat(queued.getStatus()).isEqualTo(ImportBatchStatus.CANCELLED);
        assertThat(queued.isBlocked()).isTrue();
        assertThat(queued.getStatusReason()).isEqualTo(ImportBatch.SUBJECT_BLOCK_REASON);
        // idempotente: a segunda vez só confirma
        assertThat(queued.block(BlockReason.SUBJECT_ANONYMIZED, NOW.plusSeconds(5), system))
                .isEqualTo(BlockEffect.ALREADY_FINISHED);
        assertThat(queued.getBlockedAt()).isEqualTo(NOW);

        ImportBatch done = batch(ImportMode.VALIDATE_ONLY);
        done.begin(NOW, 1);
        done.recordBlock(tally(1, 0), 0, 1, 0);
        done.complete(NOW, 0, 0);
        assertThat(done.block(BlockReason.SUBJECT_ANONYMIZED, NOW, system)).isEqualTo(BlockEffect.ALREADY_FINISHED);
        assertThat(done.getStatus()).isEqualTo(ImportBatchStatus.VALIDATED);
        assertThat(done.isBlocked()).isTrue();
    }

    @Test
    void blockAsksARunningBatchToStopAtTheEndOfTheBlock() {
        UUID system = br.com.gestorcobranca.shared.security.Actor.SYSTEM_ID;
        ImportBatch running = batch(ImportMode.IMPORT);
        running.begin(NOW, 10);
        assertThat(running.block(BlockReason.SUBJECT_ANONYMIZED, NOW, system)).isEqualTo(BlockEffect.STOP_REQUESTED);
        assertThat(running.getStatus()).isEqualTo(ImportBatchStatus.PROCESSING);
        assertThat(running.isCancelRequested()).isTrue();
        assertThat(running.getCancelRequestedBy()).isEqualTo(system);
        // pedido do usuário anterior é mantido; o bloqueio não falha por isso
        ImportBatch cancelled = batch(ImportMode.IMPORT);
        cancelled.begin(NOW, 10);
        UUID user = UUID.randomUUID();
        cancelled.requestCancel(NOW, user, "Pedido do usuário");
        assertThat(cancelled.block(BlockReason.SUBJECT_ANONYMIZED, NOW, system)).isEqualTo(BlockEffect.STOP_REQUESTED);
        assertThat(cancelled.getCancelRequestedBy()).isEqualTo(user);
        cancelled.stopCancelled(NOW, 0, 0);
        assertThat(cancelled.getStatus()).isEqualTo(ImportBatchStatus.CANCELLED);
        assertThat(cancelled.isBlocked()).isTrue();
    }

    @Test
    void blockCancelsAPreviewAwaitingFormatAndStopsAValidatingOne() {
        UUID system = br.com.gestorcobranca.shared.security.Actor.SYSTEM_ID;
        ImportBatch awaiting = new ImportBatch(UUID.randomUUID(), UUID.randomUUID(), FileLayout.standard(ImportFormat.CSV),
                ImportSource.CSV, ImportMode.VALIDATE_ONLY, new ImportBatch.StoredContent("tenants/x/imports/f.csv", "f.csv",
                "text/csv", 10, SHA), null, null, new ImportBatch.Options(UUID.randomUUID(), null, null));
        awaiting.awaitFormat(NOW, 3, "{}", null);
        assertThat(awaiting.block(BlockReason.SUBJECT_ANONYMIZED, NOW, system)).isEqualTo(BlockEffect.CANCELLED_NOW);
        assertThat(awaiting.getStatus()).isEqualTo(ImportBatchStatus.CANCELLED);

        ImportBatch validating = batch(ImportMode.VALIDATE_ONLY);
        validating.begin(NOW, 10);
        assertThat(validating.block(BlockReason.SUBJECT_ANONYMIZED, NOW, system)).isEqualTo(BlockEffect.STOP_REQUESTED);
        assertThat(validating.getStatus()).isEqualTo(ImportBatchStatus.VALIDATING);
        assertThat(validating.isCancelRequested()).isTrue();
    }

    @Test
    void subjectFoundInTheLoadBlocksWithoutCancellingAndKeepsTheFirstInstant() {
        ImportBatch running = batch(ImportMode.IMPORT);
        running.begin(NOW, 10);
        assertThat(running.markSubjectFound(NOW)).isTrue();
        assertThat(running.markSubjectFound(NOW.plusSeconds(30))).isFalse();
        assertThat(running.getBlockedAt()).isEqualTo(NOW);
        assertThat(running.isCancelRequested()).isFalse();
        assertThat(running.getStatus()).isEqualTo(ImportBatchStatus.PROCESSING);
    }
}
