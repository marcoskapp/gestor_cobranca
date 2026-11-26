package br.com.gestorcobranca.ingestion;

import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportBatchView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportSubjectBlockUseCases;
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
    @Autowired
    br.com.gestorcobranca.ingestion.application.port.in.ImportSubjectBlockUseCases subjectBlocks;
    @Autowired
    br.com.gestorcobranca.pendingwork.application.port.in.PendingWorkUseCases pendingWork;
    @Autowired
    org.springframework.context.ApplicationEventPublisher eventPublisher;


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

    @Test
    void debtorAnonymizationDeletesTheWholeLoadFileThatContainsTheDebtor() {
        ImportBatchView mixed = uploadCsv("misturado.csv", HEADER + "\n" + line(cpf(3), "Carla", "R-3", "100,00", null)
                + "\n" + line(cpf(4), "Davi", "R-4", "50,00", null) + "\n", "IMPORT");
        ImportBatchView other = uploadCsv("outro.csv", HEADER + "\n" + line(cpf(5), "Eva", "R-5", "70,00", null)
                + "\n", "IMPORT");
        runJobs();
        String mixedKey = fileKey(mixed.id());
        UUID carla = system(() -> jdbcTemplate.queryForObject("select debtor_id from ingestion.import_batch_row "
                + "where batch_id = ? and debtor_id is not null order by row_number limit 1", UUID.class, mixed.id()));

        UUID eventId = br.com.gestorcobranca.shared.domain.Ids.newId();
        tenantExecution.inTenant(tenant, "test.publish", () -> eventPublisher.publishEvent(
                new br.com.gestorcobranca.shared.events.debt.DebtorAnonymized(eventId, tenant, java.time.Instant.now(),
                        carla)));
        long deadline = System.currentTimeMillis() + 15_000;
        while (count("select count(*) from shared.consumed_event where consumer = 'ingestion.debtor-anonymized' "
                + "and event_id = ?", eventId) == 0) {
            assertThat(System.currentTimeMillis()).as("consumo da importação").isLessThan(deadline);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        // o arquivo inteiro sai (mistura vários devedores); o lote fica, com contadores e trilha
        assertThat(fileKey(mixed.id())).isNull();
        assertThat(batch(mixed.id()).fileDeletedAt()).isNotNull();
        assertThat(batch(mixed.id()).counters().rowsValid()).isEqualTo(2);
        assertThatThrownBy(() -> system(() -> fileStorage.open(mixedKey))).isInstanceOf(RuntimeException.class);
        assertThat(count("select count(*) from shared.audit_event where tenant_id = ? and action = 'import_batch.file_deleted' "
                + "and entity_id = ? and reason like 'anonimização%'", tenant, mixed.id())).isEqualTo(1);
        // arquivo sem o titular fica
        assertThat(fileKey(other.id())).isNotNull();
    }

    /** SEG-20261124-D01h (decisões 1469 e 1488): conferência aberta com o titular é encerrada na hora e o arquivo sai. */
    @Test
    void debtorAnonymizationBlocksTheBatchesClosesTheOpenReviewAndDeletesTheFileAtOnce() {
        String csv = HEADER + "\n" + line(cpf(6), "Fabio", "R-6", "10,00", null) + "\n";
        ImportBatchView closed = uploadCsv("encerrado.csv", csv, "IMPORT");
        runJobs();
        UUID fabio = system(() -> jdbcTemplate.queryForObject("select debtor_id from ingestion.import_batch_row "
                + "where batch_id = ? and debtor_id is not null limit 1", UUID.class, closed.id()));
        String closedKey = fileKey(closed.id());
        // nova conferência a partir do mesmo lote: a prévia usa o mesmo arquivo
        var review = asImporterOf(creditorA, () -> reviews.startFromBatch(closed.id()));
        runJobs();
        assertThat(system(() -> reviews.get(review.id())).status()).isEqualTo("OPEN");

        IngestionTestHooks.anonymizeDebtor(context, tenant, fabio);
        // conferência cancelada pelo sistema, lotes bloqueados, arquivo apagado na hora (nada pendente)
        assertThat(system(() -> reviews.get(review.id())).status()).isEqualTo("CANCELLED");
        assertThat(count("select count(*) from ingestion.import_batch where tenant_id = ? and file_sha256 = "
                + "(select file_sha256 from ingestion.import_batch where id = ?) and blocked_reason = 'SUBJECT_ANONYMIZED'",
                tenant, closed.id())).isEqualTo(2);
        assertThat(fileKey(closed.id())).isNull();
        assertThat(stored(closedKey)).isFalse();
        assertThat(count("select count(*) from ingestion.pending_file_erasure where tenant_id = ?", tenant)).isZero();
        assertThat(count("select count(*) from shared.audit_event where tenant_id = ? "
                + "and action = 'import_review.closed_by_system' and entity_id = ?", tenant, review.id())).isEqualTo(1);
        // registro do bloqueio com a linha do titular (referência sem dado pessoal)
        assertThat(system(() -> jdbcTemplate.queryForList("select l.row_number, l.reference, l.matched_by "
                + "from ingestion.import_subject_block b join ingestion.import_subject_block_line l on l.block_id = b.id "
                + "where b.batch_id = ?", closed.id()))).singleElement().satisfies(r -> {
                    assertThat(r.get("row_number")).isEqualTo(2L);
                    assertThat(r.get("reference")).isEqualTo("R-6");
                    assertThat(r.get("matched_by")).isEqualTo("DEBTOR");
                });

        // lote bloqueado: sem reprocessar, sem nova conferência; o mesmo arquivo é recusado no reenvio (também prévia)
        assertThatThrownBy(() -> asImporterOf(creditorA, () -> batches.reprocess(closed.id(),
                new ReprocessCommand("VALIDATE_ONLY", null))))
                .hasFieldOrPropertyWithValue("code", "import_batch.blocked_subject_anonymized");
        assertThatThrownBy(() -> asImporterOf(creditorA, () -> reviews.startFromBatch(closed.id())))
                .hasFieldOrPropertyWithValue("code", "import_batch.blocked_subject_anonymized");
        assertThatThrownBy(() -> uploadCsv("de-novo.csv", csv, "VALIDATE_ONLY"))
                .hasFieldOrPropertyWithValue("code", "import.file_blocked_subject_anonymized");
        assertThatThrownBy(() -> uploadCsv("de-novo.csv", csv, "IMPORT", true, "Forçar"))
                .hasFieldOrPropertyWithValue("code", "import.file_blocked_subject_anonymized");
        // o arquivo corrigido (sem a linha) passa
        ImportBatchView fixed = uploadCsv("corrigido.csv", HEADER + "\n" + line(cpf(8), "Hugo", "R-8", "20,00", null)
                + "\n", "VALIDATE_ONLY");
        assertThat(fixed.status()).isEqualTo("RECEIVED");
    }

    /** Lote em processamento quando o titular é anonimizado: para no fim do bloco e o encerramento conclui a remoção. */
    @Test
    void runningBatchOfTheSubjectStopsAndItsFileIsErasedWhenItFinishes() {
        ImportBatchView first = uploadCsv("rodando.csv", HEADER + "\n" + line(cpf(12), "Iara", "R-12", "10,00", null)
                + "\n", "IMPORT");
        runJobs();
        UUID iara = system(() -> jdbcTemplate.queryForObject("select debtor_id from ingestion.import_batch_row "
                + "where batch_id = ? and debtor_id is not null limit 1", UUID.class, first.id()));
        String key = fileKey(first.id());
        // reprocessamento do mesmo arquivo, posto em processamento (sem ler o arquivo ainda)
        ImportBatchView again = asImporterOf(creditorA, () -> batches.reprocess(first.id(),
                new ReprocessCommand("VALIDATE_ONLY", null)));
        system(() -> jdbcTemplate.update("update ingestion.import_batch set status = 'VALIDATING', started_at = now(), "
                + "total_rows = 1 where id = ?", again.id()));

        IngestionTestHooks.anonymizeDebtor(context, tenant, iara);
        assertThat(system(() -> jdbcTemplate.queryForObject("select effect from ingestion.import_subject_block "
                + "where batch_id = ?", String.class, again.id()))).isEqualTo("STOP_REQUESTED");
        assertThat(batch(again.id()).status()).isEqualTo("VALIDATING");
        // arquivo em uso: remoção pendente
        assertThat(stored(key)).isTrue();
        assertThat(count("select count(*) from ingestion.pending_file_erasure where tenant_id = ? and file_key = ?",
                tenant, key)).isEqualTo(1);

        // a próxima fatia vê o pedido do sistema, encerra sem ler o arquivo e agenda a conclusão da remoção
        runJobs();
        assertThat(batch(again.id()).status()).isEqualTo("CANCELLED");
        assertThat(count("select count(*) from shared.processing_job where tenant_id = ? "
                + "and type = 'ingestion.pending-erasure'", tenant)).isEqualTo(1);
        IngestionTestHooks.runPendingErasureJob(context, tenant, again.id());
        assertThat(stored(key)).isFalse();
        assertThat(count("select count(*) from ingestion.pending_file_erasure where tenant_id = ?", tenant)).isZero();
    }

    /** SEG-20261126-M08 (b) e M11: reenvio sob outra chave, reentrega do evento e isolamento das tabelas novas. */
    @Test
    void sameContentUnderAnotherKeyIsBlockedTooAndRedeliveryDoesNotRepeatTheBlock() {
        String csv = HEADER + "\n" + line(cpf(20), "Joana", "R-20", "10,00", null) + "\n";
        ImportBatchView imported = uploadCsv("a.csv", csv, "IMPORT");
        runJobs();
        UUID joana = system(() -> jdbcTemplate.queryForObject("select debtor_id from ingestion.import_batch_row "
                + "where batch_id = ? and debtor_id is not null limit 1", UUID.class, imported.id()));
        // o mesmo conteúdo enviado de novo (outra chave), ainda na fila e sem linhas
        ImportBatchView again = uploadCsv("b.csv", csv, "VALIDATE_ONLY");
        String againKey = fileKey(again.id());

        UUID eventId = br.com.gestorcobranca.shared.domain.Ids.newId();
        IngestionTestHooks.anonymizeDebtor(context, tenant, joana, eventId);
        assertThat(batch(again.id()).status()).isEqualTo("CANCELLED");
        assertThat(fileKey(again.id())).isNull();
        assertThat(stored(againKey)).isFalse();
        // o job do lote cancelado na fila saiu da fila
        assertThat(count("select count(*) from shared.processing_job where tenant_id = ? and status = 'PENDING' "
                + "and payload::text like ?", tenant, "%" + again.id() + "%")).isZero();
        assertThatThrownBy(() -> asImporterOf(creditorA, () -> reviews.startFromBatch(again.id())))
                .hasFieldOrPropertyWithValue("code", "import_batch.blocked_subject_anonymized");

        // reentrega do mesmo evento: nenhum bloqueio nem auditoria a mais
        IngestionTestHooks.anonymizeDebtor(context, tenant, joana, eventId);
        assertThat(count("select count(*) from ingestion.import_subject_block where tenant_id = ?", tenant)).isEqualTo(2);
        assertThat(count("select count(*) from shared.audit_event where tenant_id = ? "
                + "and action = 'import_batch.blocked_subject_anonymized'", tenant)).isEqualTo(2);

        // outra assessoria não lê nem grava linhas do bloqueio
        UUID blockId = system(() -> jdbcTemplate.queryForObject("select id from ingestion.import_subject_block "
                + "where batch_id = ?", UUID.class, imported.id()));
        UUID otherTenant = tenantExecution.callInTenant(br.com.gestorcobranca.shared.domain.Ids.newId(), "test.setup",
                () -> context.getBean(br.com.gestorcobranca.organization.application.port.in.TenantAdministration.class)
                        .createTenant(new br.com.gestorcobranca.organization.application.port.in.TenantAdministration
                                .CreateTenantCommand("ing-x" + UUID.randomUUID().toString().substring(0, 6), "Outra Ltda",
                                "Outra", "45.997.418/0001-53")).id());
        assertThat(tenantExecution.callInTenant(otherTenant, "test", () -> jdbcTemplate.queryForObject(
                "select count(*) from ingestion.import_subject_block_line", Long.class))).isZero();
        assertThatThrownBy(() -> tenantExecution.callInTenant(otherTenant, "test", () -> jdbcTemplate.update(
                "insert into ingestion.import_subject_block_line (block_id, position, row_number, reference, matched_by) "
                        + "values (?, 9, 9, 'x', 'DEBTOR')", blockId)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    /** Fatia 5 (decisão 1497): tela do lote bloqueado, pedido de novo arquivo e pendência de quem importa. */
    @Test
    void blockedBatchShowsTheFileRequestAndRegistersItOnce() {
        String csv = HEADER + "\n" + line(cpf(21), "Lia", "R-21", "10,00", null) + "\n"
                + line(cpf(22), "Max", "R-22", "20,00", null) + "\n";
        ImportBatchView imported = uploadCsv("lia.csv", csv, "IMPORT");
        runJobs();
        UUID lia = system(() -> jdbcTemplate.queryForObject("select debtor_id from ingestion.import_batch_row "
                + "where batch_id = ? and row_number = 2", UUID.class, imported.id()));
        // lote sem bloqueio não tem a tela (404, como fora do escopo)
        assertThatThrownBy(() -> asImporterOf(creditorA, () -> subjectBlocks.forBatch(imported.id())))
                .isInstanceOf(br.com.gestorcobranca.shared.domain.ResourceNotFoundException.class);
        IngestionTestHooks.anonymizeDebtor(context, tenant, lia);

        var view = asImporterOf(creditorA, () -> subjectBlocks.forBatch(imported.id()));
        assertThat(view.lines()).singleElement().satisfies(l -> {
            assertThat(l.rowNumber()).isEqualTo(2);
            assertThat(l.reference()).isEqualTo("R-21");
        });
        assertThat(view.message()).contains("\"lia.csv\"", "Linha 2: título R-21", view.protocol())
                .doesNotContain("Lia", "Max", "R-22");
        assertThat(view.request()).isNull();
        assertThat(batch(imported.id()).blockedReason()).isEqualTo("SUBJECT_ANONYMIZED");
        // filtro dos bloqueados e pendência de quem importa
        assertThat(asImporterOf(creditorA, () -> batches.search(new ImportBatchUseCases.BatchSearch(null, null, null,
                null, null, null, "PENDING_REQUEST"), br.com.gestorcobranca.shared.paging.PageQuery.of(0, 20)).content()))
                .extracting(ImportBatchView::id).containsExactly(imported.id());
        assertThat(asImporterOf(creditorA, () -> pendingWork.mine()).items())
                .filteredOn(i -> i.type().equals("ingestion.blocked_batches")).singleElement()
                .satisfies(i -> assertThat(i.route()).isEqualTo("/importacao/" + imported.id()));

        // outro credor não vê nem registra (404); data futura ou anterior ao bloqueio é recusada
        assertThatThrownBy(() -> asImporterOf(creditorB, () -> subjectBlocks.forBatch(imported.id())))
                .isInstanceOf(br.com.gestorcobranca.shared.domain.ResourceNotFoundException.class);
        assertThatThrownBy(() -> asImporterOf(creditorB, () -> subjectBlocks.registerRequestSent(imported.id(),
                new ImportSubjectBlockUseCases.RegisterRequestCommand(java.time.Instant.now(), "EMAIL"))))
                .isInstanceOf(br.com.gestorcobranca.shared.domain.ResourceNotFoundException.class);
        assertThatThrownBy(() -> asImporterOf(creditorA, () -> subjectBlocks.registerRequestSent(imported.id(),
                new ImportSubjectBlockUseCases.RegisterRequestCommand(java.time.Instant.now().plusSeconds(3600),
                        "EMAIL")))).hasFieldOrPropertyWithValue("code", "import_file_request.sent_at_future");
        assertThatThrownBy(() -> asImporterOf(creditorA, () -> subjectBlocks.registerRequestSent(imported.id(),
                new ImportSubjectBlockUseCases.RegisterRequestCommand(view.blockedAt().minusSeconds(60), "EMAIL"))))
                .hasFieldOrPropertyWithValue("code", "import_file_request.sent_at_before_block");
        // em processamento o pedido espera o fim (a lista de linhas ainda pode crescer)
        system(() -> jdbcTemplate.update("update ingestion.import_batch set status = 'PROCESSING', finished_at = null "
                + "where id = ?", imported.id()));
        assertThatThrownBy(() -> asImporterOf(creditorA, () -> subjectBlocks.registerRequestSent(imported.id(),
                new ImportSubjectBlockUseCases.RegisterRequestCommand(java.time.Instant.now(), "EMAIL"))))
                .hasFieldOrPropertyWithValue("code", "import_file_request.batch_in_progress");
        assertThat(asImporterOf(creditorA, () -> subjectBlocks.forBatch(imported.id())).inProgress()).isTrue();
        system(() -> jdbcTemplate.update("update ingestion.import_batch set status = 'COMPLETED', finished_at = now() "
                + "where id = ?", imported.id()));
        var sent = asImporterOf(creditorA, () -> subjectBlocks.registerRequestSent(imported.id(),
                new ImportSubjectBlockUseCases.RegisterRequestCommand(java.time.Instant.now(), "EMAIL")));
        assertThat(sent.request()).satisfies(r -> {
            assertThat(r.channel()).isEqualTo("EMAIL");
            assertThat(r.registeredAt()).isNotNull();
        });
        assertThatThrownBy(() -> asImporterOf(creditorA, () -> subjectBlocks.registerRequestSent(imported.id(),
                new ImportSubjectBlockUseCases.RegisterRequestCommand(java.time.Instant.now(), "PHONE"))))
                .hasFieldOrPropertyWithValue("code", "import_file_request.already_registered");
        assertThat(count("select count(*) from shared.audit_event where tenant_id = ? "
                + "and action = 'import_batch.file_request_sent' and entity_id = ?", tenant, imported.id())).isEqualTo(1);
        assertThat(asImporterOf(creditorA, () -> pendingWork.mine()).items())
                .noneSatisfy(i -> assertThat(i.type()).isEqualTo("ingestion.blocked_batches"));
        // o filtro separa os recusados com e sem pedido (destino da pendência: sem pedido)
        assertThat(asImporterOf(creditorA, () -> batches.search(new ImportBatchUseCases.BatchSearch(null, null, null,
                null, null, null, "REQUESTED"), br.com.gestorcobranca.shared.paging.PageQuery.of(0, 20)).content()))
                .extracting(ImportBatchView::id).containsExactly(imported.id());
        assertThat(asImporterOf(creditorA, () -> batches.search(new ImportBatchUseCases.BatchSearch(null, null, null,
                null, null, null, "PENDING_REQUEST"), br.com.gestorcobranca.shared.paging.PageQuery.of(0, 20)).content()))
                .isEmpty();
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update ingestion.import_file_request set channel = 'PHONE'"))).isInstanceOf(
                org.springframework.dao.DataAccessException.class);
    }

    private boolean stored(String key) {
        return system(() -> {
            try (java.io.InputStream in = fileStorage.open(key)) {
                return in.read() >= 0;
            } catch (java.io.IOException | java.io.UncheckedIOException e) {
                return false;
            }
        });
    }

    private String fileKey(UUID batchId) {
        return system(() -> jdbcTemplate.queryForObject("select file_key from ingestion.import_batch where id = ?",
                String.class, batchId));
    }
}
