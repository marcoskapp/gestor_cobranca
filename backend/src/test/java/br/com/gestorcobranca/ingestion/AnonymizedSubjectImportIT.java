package br.com.gestorcobranca.ingestion;

import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases.OpenRequestCommand;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.ComponentAmounts;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.PaymentApplication;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportBatchView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportIssueView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases;
import br.com.gestorcobranca.ingestion.application.service.IngestionTestHooks;
import br.com.gestorcobranca.ingestion.domain.model.ParsedRow;
import br.com.gestorcobranca.ingestion.domain.model.TitleSituation;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.domain.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * SEG-20261124-D01h (decisões 1469 e 1470): a carga não traz de volta o titular anonimizado — a linha com o título ou o
 * contrato dele é recusada por bloco, antes de validar e gravar, sem criar devedor; as demais linhas seguem.
 */
class AnonymizedSubjectImportIT extends IngestionTestSupport {

    @Autowired
    DebtLedgerPort ledger;

    @Autowired
    DebtorUseCases debtors;

    @Autowired
    ImportReviewUseCases reviews;

    @Autowired
    DataSubjectRequestUseCases requests;

    @Autowired
    org.springframework.context.ApplicationContext context;

    @Test
    void newLoadRejectsRowsOfAnonymizedSubjectAndImportsTheRest() {
        // titular com título quitado e anonimizado
        ImportBatchView first = uploadCsv("primeira.csv", HEADER + "\n" + line(cpf(7), "Gabriela", "R-7", "100,00", null)
                + "\n", "IMPORT");
        runJobs();
        UUID gabriela = system(() -> jdbcTemplate.queryForObject("select debtor_id from ingestion.import_batch_row "
                + "where batch_id = ? and debtor_id is not null", UUID.class, first.id()));
        UUID title = system(() -> jdbcTemplate.queryForObject("select title_id from ingestion.import_batch_row "
                + "where batch_id = ?", UUID.class, first.id()));
        system(() -> ledger.applyPayment(new PaymentApplication(title, Ids.newId(),
                ComponentAmounts.principalOnly(new BigDecimal("100.00")), Instant.now())));
        system(() -> debtors.anonymize(gabriela, "Solicitação do titular"));
        long debtorsBefore = count("select count(*) from debt.debtor");

        // a mesma linha (título e contrato), um título novo no contrato dela, uma linha "pago" do título e outra pessoa
        String csv = HEADER + "\n" + line(cpf(7), "Gabriela", "R-7", "100,00", null)
                + "\n" + "%s;Gabriela;(11) 98765-4321;S;;C-R-7;R-7B;2;10/04/2026;50,00;;;".formatted(cpf(7))
                + "\n" + line(cpf(7), "Gabriela", "R-7", "100,00", "PAGO")
                + "\n" + line(cpf(9), "Ivo", "R-9", "70,00", null) + "\n";
        // na conferência (prévia), as linhas do titular são recusadas e os valores por linha vão sem identificadores
        // nem índice do documento (import_row_value é imutável)
        byte[] bytes = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var review = asImporterOf(creditorA, () -> reviews.start(new ImportReviewUseCases.StartReviewCommand(portfolioA,
                null, "previa.csv", "text/csv", bytes.length, () -> new java.io.ByteArrayInputStream(bytes))));
        runJobs();
        UUID preview = system(() -> jdbcTemplate.queryForObject("select id from ingestion.import_batch where review_id = ? "
                + "and mode = 'VALIDATE_ONLY'", UUID.class, review.id()));
        assertThat(subjectIssues(preview)).extracting(ImportIssueView::rowNumber, ImportIssueView::field)
                .containsExactly(tuple(2L, "id_titulo"), tuple(3L, "contrato"), tuple(4L, "id_titulo"));
        assertThat(subjectIssues(preview)).allSatisfy(i -> {
            assertThat(i.severity()).isEqualTo("ERROR");
            assertThat(i.message()).contains("titular cujos dados foram eliminados").doesNotContain("Gabriela");
        });
        assertThat(system(() -> jdbcTemplate.queryForList("select v.row_number, v.external_id, v.contract_number, "
                + "v.debtor_key from ingestion.import_row_value v where v.batch_id = ? and v.row_number in (2, 3, 4) "
                + "order by v.row_number", preview)))
                .hasSize(3).allSatisfy(r -> {
                    assertThat(r.get("external_id")).isNull();
                    assertThat(r.get("contract_number")).isNull();
                    assertThat(r.get("debtor_key")).isNull();
                });
        assertThat(system(() -> jdbcTemplate.queryForObject("select external_id from ingestion.import_row_value "
                + "where batch_id = ? and row_number = 5", String.class, preview))).isEqualTo("R-9");
        // decisão 1496: a prévia com o titular fica bloqueada, a conferência é cancelada pelo sistema e o arquivo sai
        assertThat(system(() -> reviews.get(review.id())).status()).isEqualTo("CANCELLED");
        assertThat(count("select count(*) from ingestion.import_batch where id = ? and blocked_reason is not null",
                preview)).isEqualTo(1);
        assertThat(count("select count(*) from ingestion.import_subject_block where batch_id = ? "
                + "and effect = 'FOUND_IN_LOAD' and lines = 3", preview)).isEqualTo(1);
        IngestionTestHooks.runPendingErasureJob(context, tenant, preview);
        assertThat(count("select count(*) from ingestion.import_batch where id = ? and file_key is null", preview))
                .isEqualTo(1);
        // a remoção cita a origem certa: o lote que achou o titular, não um evento de anonimização
        assertThat(count("select count(*) from shared.audit_event where tenant_id = ? and action = 'import_batch.file_deleted' "
                + "and metadata->>'originBatchId' = ? and metadata->>'sourceEventId' is null", tenant, preview.toString()))
                .isEqualTo(1);
        // o mesmo arquivo não volta: nem importação, nem prévia
        assertThatThrownBy(() -> uploadCsv("segunda.csv", csv, "IMPORT", true, "Teste do titular anonimizado"))
                .hasFieldOrPropertyWithValue("code", "import.file_blocked_subject_anonymized");

        // importação de outro arquivo com o titular e outra pessoa: só a outra pessoa entra; o lote fica bloqueado
        String other = HEADER + "\n" + line(cpf(7), "Gabriela", "R-7", "100,00", null)
                + "\n" + line(cpf(9), "Ivo", "R-9", "70,00", null) + "\n" + line(cpf(10), "Jade", "R-10", "5,00", null)
                + "\n";
        ImportBatchView again = uploadCsv("terceira.csv", other, "IMPORT");
        runJobs();
        ImportBatchView done = batch(again.id());
        assertThat(done.status()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(subjectIssues(again.id())).extracting(ImportIssueView::rowNumber).containsExactly(2L);
        assertThat(count("select count(*) from debt.debtor")).isEqualTo(debtorsBefore + 2);
        assertThat(count("select count(*) from ingestion.import_batch_row where batch_id = ? and external_id = 'R-7' "
                + "and debtor_id is not null", again.id())).isZero();
        assertThat(count("select count(*) from ingestion.import_batch where id = ? and blocked_reason is not null",
                again.id())).isEqualTo(1);
        assertThat(count("select count(*) from shared.processing_job where tenant_id = ? "
                + "and type = 'ingestion.pending-erasure'", tenant)).isGreaterThanOrEqualTo(1);
        // o título conservado não foi mexido pela carga
        assertThat(system(() -> jdbcTemplate.queryForObject("select status from debt.debt_title where id = ?",
                String.class, title))).isEqualTo("SETTLED");
    }

    @Test
    void paidRowOfAnonymizedSubjectsTitleIsRejectedInsteadOfTouchingTheTitle() {
        ImportBatchView first = uploadCsv("p1.csv", HEADER + "\n" + line(cpf(11), "Helena", "R-11", "80,00", null)
                + "\n", "IMPORT");
        runJobs();
        UUID helena = system(() -> jdbcTemplate.queryForObject("select debtor_id from ingestion.import_batch_row "
                + "where batch_id = ? and debtor_id is not null", UUID.class, first.id()));
        UUID title = system(() -> jdbcTemplate.queryForObject("select title_id from ingestion.import_batch_row "
                + "where batch_id = ?", UUID.class, first.id()));
        system(() -> ledger.applyPayment(new PaymentApplication(title, Ids.newId(),
                ComponentAmounts.principalOnly(new BigDecimal("80.00")), Instant.now())));
        system(() -> debtors.anonymize(helena, "Solicitação do titular"));

        ImportBatchView paid = uploadCsv("p2.csv", HEADER + "\n" + line(cpf(11), "Helena", "R-11", "80,00", "PAGO")
                + "\n", "IMPORT");
        runJobs();
        assertThat(subjectIssues(paid.id())).singleElement()
                .satisfies(i -> assertThat(i.field()).isEqualTo("id_titulo"));
    }

    /**
     * SEG-20261125-K07: a defesa do {@code RowWriter}. A linha "pago" chega à gravação sem passar pela recusa por bloco
     * (o titular anonimizado entre a leitura do bloco e a gravação) e ainda assim é recusada: não registra o título
     * conservado no lote novo nem mexe nele. O identificador tem o CPF embutido, então o título já está na forma
     * anonimizada (decisão 1476) e a defesa o reconhece pela chave.
     */
    @Test
    void paidRowReachingTheWriterAfterTheBlockCheckIsStillRejected() {
        rowReachingTheWriterIsRejected(15, "R-" + cpf(15), TitleSituation.PAID, true);
    }

    /** Idem para a linha "cancelado", com identificador sem documento (continua cru; chave calculada dele). */
    @Test
    void cancelledRowReachingTheWriterAfterTheBlockCheckIsStillRejected() {
        rowReachingTheWriterIsRejected(17, "R-17", TitleSituation.CANCELLED, false);
    }

    private void rowReachingTheWriterIsRejected(int seed, String externalId, TitleSituation situation,
                                                boolean replaced) {
        ImportBatchView first = uploadCsv("k%d-1.csv".formatted(seed), HEADER + "\n"
                + line(cpf(seed), "Lia", externalId, "60,00", null) + "\n", "IMPORT");
        runJobs();
        UUID lia = system(() -> jdbcTemplate.queryForObject("select debtor_id from ingestion.import_batch_row "
                + "where batch_id = ? and debtor_id is not null", UUID.class, first.id()));
        UUID title = system(() -> jdbcTemplate.queryForObject("select title_id from ingestion.import_batch_row "
                + "where batch_id = ?", UUID.class, first.id()));
        system(() -> ledger.applyPayment(new PaymentApplication(title, Ids.newId(),
                ComponentAmounts.principalOnly(new BigDecimal("60.00")), Instant.now())));
        system(() -> debtors.anonymize(lia, "Solicitação do titular"));
        String stored = system(() -> jdbcTemplate.queryForObject("select external_id from debt.debt_title where id = ?",
                String.class, title));
        if (replaced) {
            assertThat(stored).startsWith("anon:");
        } else {
            assertThat(stored).isEqualTo(externalId);
        }

        // lote de outra pessoa, ainda não processado; a linha do titular vai direto à gravação
        ImportBatchView second = uploadCsv("k%d-2.csv".formatted(seed), HEADER + "\n"
                + line(cpf(seed + 1), "Mia", "R-" + (seed + 1), "10,00", null) + "\n", "IMPORT");
        ParsedRow row = new ParsedRow(2, Document.parse(cpf(seed)), "Lia", null, List.of(), null, externalId, 1,
                LocalDate.of(2026, 3, 10), new BigDecimal("60.00"), null, null, situation, null, null, null);
        List<String> errors = asImporterOf(creditorA,
                () -> IngestionTestHooks.writeRow(context, second.id(), creditorA, portfolioA, row));

        assertThat(errors).containsExactly(DebtIngestionPort.SUBJECT_ANONYMIZED);
        // sem a defesa, a linha seria gravada como "skipped" no lote novo, ligada ao título conservado
        assertThat(count("select count(*) from ingestion.import_batch_row where batch_id = ?", second.id())).isZero();
        assertThat(count("select count(*) from ingestion.import_batch_row where title_id = ?", title)).isEqualTo(1);
        // título conservado intacto (titular anonimizado não tem título em cobrança: não há suspensão a conferir)
        assertThat(system(() -> jdbcTemplate.queryForMap("select status, external_id from debt.debt_title where id = ?",
                title))).satisfies(t -> {
                    assertThat(t.get("status")).isEqualTo("SETTLED");
                    assertThat(t.get("external_id")).isEqualTo(stored);
                });
    }

    /** Decisão 1488: a prévia que trouxe o título do titular (sem gravar o devedor) também é bloqueada. */
    @Test
    void earlierPreviewWithTheSubjectsTitleIsBlockedByTheTitleReference() {
        ImportBatchView first = uploadCsv("t1.csv", HEADER + "\n" + line(cpf(13), "Joana", "R-13", "90,00", null)
                + "\n", "IMPORT");
        runJobs();
        UUID joana = system(() -> jdbcTemplate.queryForObject("select debtor_id from ingestion.import_batch_row "
                + "where batch_id = ? and debtor_id is not null", UUID.class, first.id()));
        UUID title = system(() -> jdbcTemplate.queryForObject("select title_id from ingestion.import_batch_row "
                + "where batch_id = ?", UUID.class, first.id()));
        // prévia com o título dela e outra pessoa: nenhuma linha grava devedor
        ImportBatchView preview = uploadCsv("t2.csv", HEADER + "\n" + line(cpf(13), "Joana", "R-13", "90,00", null)
                + "\n" + line(cpf(14), "Kai", "R-14", "10,00", null) + "\n", "VALIDATE_ONLY");
        runJobs();
        assertThat(count("select count(*) from ingestion.import_batch_row where batch_id = ? and debtor_id is not null",
                preview.id())).isZero();

        system(() -> ledger.applyPayment(new PaymentApplication(title, Ids.newId(),
                ComponentAmounts.principalOnly(new BigDecimal("90.00")), Instant.now())));
        // pelo caminho real: pedido do titular → portão do compliance (abre o aviso ao credor) → evento → ingestion
        var request = system(() -> {
            var opened = requests.open(new OpenRequestCommand("ANONYMIZATION", "EMAIL", "Joana", cpf(13), null,
                    "Pedido do titular", null));
            requests.verifyIdentity(opened.id(), joana);
            requests.start(opened.id(), null);
            return requests.execute(opened.id());
        });
        assertThat(request.executedAt()).isNotNull();
        long deadline = System.currentTimeMillis() + 15_000;
        while (count("select count(*) from ingestion.import_subject_block where batch_id = ?", preview.id()) == 0) {
            assertThat(System.currentTimeMillis()).as("bloqueio da prévia").isLessThan(deadline);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        assertThat(system(() -> jdbcTemplate.queryForList("select l.row_number, l.matched_by, b.effect "
                + "from ingestion.import_subject_block b join ingestion.import_subject_block_line l on l.block_id = b.id "
                + "where b.batch_id = ?", preview.id()))).singleElement().satisfies(r -> {
                    assertThat(r.get("row_number")).isEqualTo(2L);
                    assertThat(r.get("matched_by")).isEqualTo("TITLE_REFERENCE");
                    assertThat(r.get("effect")).isEqualTo("ALREADY_FINISHED");
                });
        // o bloqueio aponta para o aviso ao credor aberto pela eliminação
        UUID notice = system(() -> jdbcTemplate.queryForObject("select id from compliance.creditor_erasure_notice "
                + "where debtor_id = ? and creditor_id = ?", UUID.class, joana, creditorA));
        assertThat(count("select count(*) from ingestion.import_subject_block where batch_id in (?, ?) and notice_id = ?",
                first.id(), preview.id(), notice)).isEqualTo(2);
        // os dois arquivos (o da carga e o da prévia) saem inteiros
        assertThat(count("select count(*) from ingestion.import_batch where id in (?, ?) and file_key is null "
                + "and blocked_reason = 'SUBJECT_ANONYMIZED'", first.id(), preview.id())).isEqualTo(2);
    }

    /** SEG-20261126-M13: o mesmo conteúdo parado na fila fica bloqueado quando outro lote acha o titular na carga. */
    @Test
    void sameContentWaitingInTheQueueIsBlockedWhenAnotherBatchFindsTheSubject() {
        ImportBatchView first = uploadCsv("n1.csv", HEADER + "\n" + line(cpf(23), "Nina", "R-23", "60,00", null)
                + "\n", "IMPORT");
        runJobs();
        UUID nina = system(() -> jdbcTemplate.queryForObject("select debtor_id from ingestion.import_batch_row "
                + "where batch_id = ? and debtor_id is not null", UUID.class, first.id()));
        UUID title = system(() -> jdbcTemplate.queryForObject("select title_id from ingestion.import_batch_row "
                + "where batch_id = ?", UUID.class, first.id()));
        system(() -> ledger.applyPayment(new PaymentApplication(title, Ids.newId(),
                ComponentAmounts.principalOnly(new BigDecimal("60.00")), Instant.now())));
        system(() -> debtors.anonymize(nina, "Solicitação do titular"));

        String csv = HEADER + "\n" + line(cpf(23), "Nina", "R-23", "60,00", null) + "\n"
                + line(cpf(24), "Otto", "R-24", "15,00", null) + "\n";
        // S: na fila, sem processar (o job fica de lado no teste)
        ImportBatchView waiting = uploadCsv("s.csv", csv, "VALIDATE_ONLY");
        String waitingKey = system(() -> jdbcTemplate.queryForObject("select file_key from ingestion.import_batch "
                + "where id = ?", String.class, waiting.id()));
        system(() -> jdbcTemplate.update("update shared.processing_job set status = 'DONE' where tenant_id = ? "
                + "and payload::text like ?", tenant, "%" + waiting.id() + "%"));
        // B: o mesmo conteúdo, processado — acha o titular na carga
        ImportBatchView loaded = uploadCsv("b.csv", csv, "IMPORT", true, "Mesmo arquivo");
        runJobs();
        assertThat(count("select count(*) from ingestion.import_batch where id = ? and blocked_reason is not null",
                loaded.id())).isEqualTo(1);
        IngestionTestHooks.runPendingErasureJob(context, tenant, loaded.id());
        assertThat(batch(waiting.id()).status()).isEqualTo("CANCELLED");
        assertThat(count("select count(*) from ingestion.import_batch where id = ? and blocked_reason is not null "
                + "and file_key is null", waiting.id())).isEqualTo(1);
        assertThat(count("select count(*) from ingestion.import_subject_block where batch_id = ? "
                + "and effect = 'FOUND_IN_LOAD'", waiting.id())).isEqualTo(1);
        assertThat(waitingKey).isNotNull();
    }

    private List<ImportIssueView> subjectIssues(UUID batchId) {
        return issues(batchId).stream().filter(i -> DebtIngestionPort.SUBJECT_ANONYMIZED.equals(i.code())).toList();
    }
}
