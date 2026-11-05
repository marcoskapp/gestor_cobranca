package br.com.gestorcobranca.payment.domain.model;

import br.com.gestorcobranca.shared.domain.DomainException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ciclo da importação de extrato processada em lotes com retomada (decisão 868). */
class StatementImportTest {

    private final Instant now = Instant.parse("2026-11-05T12:00:00Z");

    private StatementImport received() {
        return new StatementImport(StatementFormat.CSV_GENERIC, null, "k", "extrato.csv", 10, "a".repeat(64),
                UUID.randomUUID());
    }

    @Test
    void startsOnceAndResumesAnInterruptedProcessingWithoutRestartingTheClock() {
        StatementImport source = received();
        assertThat(source.startOrResume(now)).isTrue();
        assertThat(source.getStatus()).isEqualTo(StatementImportStatus.PROCESSING);
        // nova tentativa depois de falha no meio: retoma, mantendo o início original
        assertThat(source.startOrResume(now.plusSeconds(60))).isTrue();
        assertThat(source.getStartedAt()).isEqualTo(now);
        source.finish(Map.of(StatementLineStatus.RECONCILED, 1L), now);
        assertThat(source.startOrResume(now)).isFalse();
    }

    @Test
    void countsComeFromTheRecordedLinesAndReplaceAnyPreviousTally() {
        StatementImport source = received();
        source.startOrResume(now);
        source.finish(Map.of(StatementLineStatus.RECONCILED, 3L, StatementLineStatus.CREATED_PAYMENT, 2L,
                StatementLineStatus.DIVERGENT, 4L, StatementLineStatus.IGNORED_DEBIT, 5L,
                StatementLineStatus.IGNORED_DUPLICATE, 1L, StatementLineStatus.ALREADY_RECONCILED, 1L), now);
        assertThat(source.getStatus()).isEqualTo(StatementImportStatus.PROCESSED);
        assertThat(source.getTotalLines()).isEqualTo(16);
        assertThat(source.getReconciledLines()).isEqualTo(5);
        assertThat(source.getCreatedPayments()).isEqualTo(2);
        assertThat(source.getDivergentLines()).isEqualTo(4);
        assertThat(source.getIgnoredLines()).isEqualTo(7);
        assertThat(source.getFinishedAt()).isEqualTo(now);
    }

    @Test
    void exhaustedAttemptsFailWithThePartialCountsAndOnlyWhileProcessing() {
        StatementImport source = received();
        assertThatThrownBy(() -> source.finish(Map.of(), now)).isInstanceOf(DomainException.class);
        source.startOrResume(now);
        source.failProcessing("statement.processing_failed", Map.of(StatementLineStatus.RECONCILED, 2L), now);
        assertThat(source.getStatus()).isEqualTo(StatementImportStatus.FAILED);
        assertThat(source.getFailureCode()).isEqualTo("statement.processing_failed");
        assertThat(source.getTotalLines()).isEqualTo(2);
        assertThat(source.getReconciledLines()).isEqualTo(2);
        assertThatThrownBy(() -> source.failProcessing("x", Map.of(), now)).isInstanceOf(DomainException.class);
    }
}
