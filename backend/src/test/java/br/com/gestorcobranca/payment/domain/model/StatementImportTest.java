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
        source.failProcessing("statement.processing_failed", 501, Map.of(StatementLineStatus.RECONCILED, 2L), now);
        assertThat(source.getStatus()).isEqualTo(StatementImportStatus.FAILED);
        assertThat(source.getFailureCode()).isEqualTo("statement.processing_failed");
        assertThat(source.getFailureLine()).isEqualTo(501);
        assertThat(source.getTotalLines()).isEqualTo(2);
        assertThat(source.getReconciledLines()).isEqualTo(2);
        assertThatThrownBy(() -> source.failProcessing("x", null, Map.of(), now)).isInstanceOf(DomainException.class);
    }

    @Test
    void wholeFileFailureHasNoLine() {
        StatementImport source = received();
        source.startOrResume(now);
        source.failProcessing("statement.unreadable", 0, Map.of(), now);
        assertThat(source.getFailureCode()).isEqualTo("statement.unreadable");
        assertThat(source.getFailureLine()).isNull();
    }

    @Test
    void retryOnlyFromFailedGoesBackToReceivedAndResumesFromTheRecordedLines() {
        StatementImport source = received();
        // recebida, em processamento e processada não aceitam nova tentativa
        assertThatThrownBy(source::retry).isInstanceOf(DomainException.class).hasMessageContaining("falha");
        source.startOrResume(now);
        assertThatThrownBy(source::retry).isInstanceOf(DomainException.class);
        source.failProcessing("statement.date_invalid", 7, Map.of(StatementLineStatus.RECONCILED, 3L), now);

        source.retry();
        assertThat(source.getStatus()).isEqualTo(StatementImportStatus.RECEIVED);
        assertThat(source.getFailureCode()).isNull();
        assertThat(source.getFailureLine()).isNull();
        assertThat(source.getFinishedAt()).isNull();
        assertThat(source.getRetryCount()).isEqualTo(1);
        // contagens parciais ficam até a nova apuração
        assertThat(source.getTotalLines()).isEqualTo(3);
        assertThatThrownBy(source::retry).isInstanceOf(DomainException.class);

        // o job retoma e conclui; a importação processada não volta
        assertThat(source.startOrResume(now.plusSeconds(60))).isTrue();
        source.finish(Map.of(StatementLineStatus.RECONCILED, 5L), now.plusSeconds(90));
        assertThat(source.getStatus()).isEqualTo(StatementImportStatus.PROCESSED);
        assertThat(source.getTotalLines()).isEqualTo(5);
        assertThatThrownBy(source::retry).isInstanceOf(DomainException.class);
    }
}
