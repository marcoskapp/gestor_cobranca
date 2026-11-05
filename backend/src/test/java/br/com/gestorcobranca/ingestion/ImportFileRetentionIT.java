package br.com.gestorcobranca.ingestion;

import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportBatchView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ReprocessCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases;
import br.com.gestorcobranca.ingestion.application.service.IngestionTestHooks;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.storage.FileStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Descarte dos arquivos de importação pela política IMPORT_FILES (LGPD) contra PostgreSQL real: só com a política
 * habilitada, só quando todos os lotes que compartilham o arquivo estão encerrados antes do corte e sem conferência
 * aberta; o lote fica (contadores e trilha) e o download, a nova conferência e o reprocessamento respondem 409.
 */
class ImportFileRetentionIT extends IngestionTestSupport {

    @Autowired
    RetentionUseCases retention;
    @Autowired
    ImportReviewUseCases reviews;
    @Autowired
    FileStorage fileStorage;
    @Autowired
    ApplicationContext context;

    @Test
    void deletesSharedFileOfFinishedBatchesAndKeepsTheOneWithAnOpenReview() {
        ImportBatchView imported = uploadCsv("antigo.csv", HEADER + "\n" + line(cpf(1), "Ana", "R-1", "100,00", null)
                + "\n", "IMPORT");
        runJobs();
        assertThat(batch(imported.id()).status()).isEqualTo("COMPLETED");
        // reprocessamento (prévia) do mesmo arquivo: compartilha a chave com o lote original
        ImportBatchView reprocessed = asImporterOf(creditorA, () -> batches.reprocess(imported.id(),
                new ReprocessCommand("VALIDATE_ONLY", null)));
        runJobs();
        assertThat(batch(reprocessed.id()).status()).isEqualTo("VALIDATED");
        String sharedKey = fileKey(imported.id());
        assertThat(fileKey(reprocessed.id())).isEqualTo(sharedKey);

        ImportBatchView reviewed = uploadCsv("em-conferencia.csv", HEADER + "\n"
                + line(cpf(2), "Bruno", "R-2", "200,00", null) + "\n", "IMPORT");
        runJobs();
        var review = asImporterOf(creditorA, () -> reviews.startFromBatch(reviewed.id()));
        runJobs();
        assertThat(system(() -> reviews.get(review.id())).status()).isEqualTo("OPEN");

        // tudo encerrado há dois meses
        system(() -> jdbcTemplate.update("update ingestion.import_batch set started_at = started_at - interval '2 months', "
                + "finished_at = finished_at - interval '2 months', "
                + "updated_at = now() - interval '2 months' where tenant_id = ?", tenant));

        // política desabilitada (padrão): nada sai
        system(() -> retention.configure("IMPORT_FILES", 1, false));
        assertThat(IngestionTestHooks.purgeImportFiles(context, tenant)).isZero();
        assertThat(fileKey(imported.id())).isEqualTo(sharedKey);

        system(() -> retention.configure("IMPORT_FILES", 1, true));
        assertThat(IngestionTestHooks.purgeImportFiles(context, tenant)).isEqualTo(1);

        // o arquivo compartilhado sai do armazenamento e dos dois lotes; os lotes ficam
        assertThat(count("select count(*) from ingestion.import_batch where id in (?, ?) and file_key is null "
                + "and file_deleted_at is not null", imported.id(), reprocessed.id())).isEqualTo(2);
        assertThatThrownBy(() -> system(() -> fileStorage.open(sharedKey))).isInstanceOf(RuntimeException.class);
        assertThat(batch(imported.id()).fileDeletedAt()).isNotNull();
        assertThat(batch(imported.id()).counters().rowsValid()).isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where tenant_id = ? "
                + "and action = 'import_batch.file_deleted' and entity_id = ?", tenant, imported.id())).isEqualTo(1);
        // conferência aberta usa o outro arquivo: fica
        assertThat(fileKey(reviewed.id())).isNotNull();
        assertThat(batch(reviewed.id()).fileDeletedAt()).isNull();

        // sem o arquivo: download, nova conferência e reprocessamento respondem 409
        assertThatThrownBy(() -> system(() -> batches.originalFile(imported.id())))
                .isInstanceOf(ConflictException.class).hasMessageContaining("descartado");
        assertThatThrownBy(() -> asImporterOf(creditorA, () -> reviews.startFromBatch(imported.id())))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("import_batch.file_deleted"));
        assertThatThrownBy(() -> asImporterOf(creditorA, () -> batches.reprocess(imported.id(),
                new ReprocessCommand("VALIDATE_ONLY", null))))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("import_batch.file_deleted"));

        // segunda execução: nada mais a descartar
        assertThat(IngestionTestHooks.purgeImportFiles(context, tenant)).isZero();
    }

    private String fileKey(UUID batchId) {
        return system(() -> jdbcTemplate.queryForObject("select file_key from ingestion.import_batch where id = ?",
                String.class, batchId));
    }
}
