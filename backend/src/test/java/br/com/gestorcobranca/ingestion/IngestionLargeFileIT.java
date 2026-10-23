package br.com.gestorcobranca.ingestion;

import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportBatchView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportIssueView;
import br.com.gestorcobranca.shared.events.debt.PortfolioLoaded;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Arquivos grandes: processamento em blocos e em fatias (cada bloco esgota a fatia de 1 ms, então
 * cada bloco vira um job), retomada sem duplicidade após perda do checkpoint, cancelamento durante
 * o processamento e limite de linhas por lote.
 */
@RecordApplicationEvents
@TestPropertySource(properties = {"app.ingestion.block-size=100", "app.ingestion.slice-duration=PT0.001S",
        "app.ingestion.max-rows=2000"})
class IngestionLargeFileIT extends IngestionTestSupport {

    @Autowired
    ApplicationEvents events;

    @Test
    void largeFileIsProcessedInBlocksAcrossSeveralJobs() {
        ImportBatchView received = uploadCsv("grande.csv", generated(1_050), null);
        int jobs = runJobs();
        ImportBatchView done = batch(received.id());
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.totalRows()).isEqualTo(1_050);
        assertThat(done.counters().rowsRead()).isEqualTo(1_050);
        assertThat(done.counters().titlesCreated()).isEqualTo(1_050);
        assertThat(done.counters().debtorsCreated()).isEqualTo(1_050);
        assertThat(done.counters().principalTotal()).isEqualByComparingTo("105000.00");
        assertThat(done.lastProcessedRow()).isEqualTo(1_051);
        // 11 blocos de até 100 linhas, um job por bloco
        assertThat(jobs).isEqualTo(11);
        assertThat(titleCount(creditorA)).isEqualTo(1_050);
        assertThat(events.stream(PortfolioLoaded.class).filter(e -> e.importBatchId().equals(received.id())))
                .singleElement().satisfies(e -> assertThat(e.debtorsAffected()).isEqualTo(1_050));
    }

    @Test
    void resumesAfterLosingTheCheckpointWithoutDuplicatingAnything() {
        ImportBatchView received = uploadCsv("retomada.csv", generated(300), null);
        runJobs(1);
        ImportBatchView partial = batch(received.id());
        assertThat(partial.status()).isEqualTo("PROCESSING");
        assertThat(partial.counters().rowsRead()).isEqualTo(100);

        // queda simulada: as 100 linhas foram gravadas, mas o checkpoint e os contadores do bloco se perderam
        system(() -> jdbcTemplate.update("""
                update ingestion.import_batch set last_processed_row = 0, rows_read = 0, rows_valid = 0,
                       titles_created = 0, principal_total = 0, issues_recorded = 0, warnings = 0
                where id = ?""", received.id()));

        runJobs();
        ImportBatchView done = batch(received.id());
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.counters().rowsRead()).isEqualTo(300);
        assertThat(done.counters().titlesCreated()).isEqualTo(300);
        assertThat(done.counters().principalTotal()).isEqualByComparingTo("30000.00");
        assertThat(titleCount(creditorA)).isEqualTo(300);
        assertThat(count("select count(*) from ingestion.import_batch_row where batch_id = ?", received.id()))
                .isEqualTo(300);
        assertThat(count("select count(*) from debt.title_ledger_entry e join debt.debt_title t on t.id = e.title_id "
                + "where t.creditor_id = ?", creditorA)).isEqualTo(300);
    }

    @Test
    void cancellationDuringProcessingStopsAtTheNextBlock() {
        ImportBatchView received = uploadCsv("cancelar.csv", generated(500), null);
        runJobs(1);
        ImportBatchView requested = asImporterOf(creditorA, () -> batches.cancel(received.id(), "Carteira errada"));
        assertThat(requested.status()).isEqualTo("PROCESSING");
        assertThat(requested.cancelRequestedAt()).isNotNull();

        runJobs();
        ImportBatchView cancelled = batch(received.id());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.statusReason()).isEqualTo("Carteira errada");
        assertThat(cancelled.counters().rowsRead()).isEqualTo(200);
        assertThat(titleCount(creditorA)).isEqualTo(200);
        // o que foi gravado já está na carteira: os casos precisam ser abertos
        assertThat(events.stream(PortfolioLoaded.class).filter(e -> e.importBatchId().equals(received.id())))
                .singleElement().satisfies(e -> assertThat(e.titlesCreated()).isEqualTo(200));

        // o restante pode ser completado reenviando o arquivo (reimportação idempotente)
        ImportBatchView rest = uploadCsv("cancelar.csv", generated(500), null);
        runJobs();
        ImportBatchView completed = batch(rest.id());
        assertThat(completed.counters().titlesCreated()).isEqualTo(300);
        assertThat(completed.counters().titlesUnchanged()).isEqualTo(200);
        assertThat(titleCount(creditorA)).isEqualTo(500);
    }

    @Test
    void fileAboveTheRowLimitFailsBeforeWritingAnything() {
        ImportBatchView received = uploadCsv("excesso.csv", generated(2_001), null);
        runJobs();
        ImportBatchView failed = batch(received.id());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.counters().rowsRead()).isZero();
        assertThat(titleCount(creditorA)).isZero();
        assertThat(issues(received.id())).extracting(ImportIssueView::rowNumber, ImportIssueView::code)
                .containsExactly(tuple(0L, "import.too_many_rows"));
    }
}
