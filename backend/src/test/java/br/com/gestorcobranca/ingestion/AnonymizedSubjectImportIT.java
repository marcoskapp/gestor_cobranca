package br.com.gestorcobranca.ingestion;

import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.ComponentAmounts;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.PaymentApplication;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportBatchView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportIssueView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases;
import br.com.gestorcobranca.shared.domain.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
        ImportBatchView preview = uploadCsv("previa.csv", csv, "VALIDATE_ONLY");
        runJobs();
        assertThat(subjectIssues(preview.id())).extracting(ImportIssueView::rowNumber, ImportIssueView::field)
                .containsExactly(tuple(2L, "id_titulo"), tuple(3L, "contrato"), tuple(4L, "id_titulo"));
        ImportBatchView again = uploadCsv("segunda.csv", csv, "IMPORT", true, "Teste do titular anonimizado");
        runJobs();
        ImportBatchView done = batch(again.id());
        assertThat(done.status()).isEqualTo("COMPLETED_WITH_ERRORS");
        // a recusa vem antes da duplicidade: a linha "pago" do mesmo título também é recusada
        assertThat(subjectIssues(again.id())).extracting(ImportIssueView::rowNumber, ImportIssueView::field)
                .containsExactly(tuple(2L, "id_titulo"), tuple(3L, "contrato"), tuple(4L, "id_titulo"));
        assertThat(subjectIssues(again.id())).allSatisfy(i -> {
            assertThat(i.severity()).isEqualTo("ERROR");
            assertThat(i.message()).contains("titular cujos dados foram eliminados").doesNotContain("Gabriela");
        });
        // só o Ivo entrou: nenhum devedor novo para a Gabriela, nenhuma linha gravada apontando para devedor dela
        assertThat(count("select count(*) from debt.debtor")).isEqualTo(debtorsBefore + 1);
        assertThat(count("select count(*) from ingestion.import_batch_row where batch_id = ? and external_id in "
                + "('R-7', 'R-7B') and debtor_id is not null", again.id())).isZero();
        // na conferência do mesmo arquivo, os valores por linha da linha recusada não guardam identificador nem índice
        // do documento (import_row_value é imutável)
        var review = asImporterOf(creditorA, () -> reviews.startFromBatch(again.id()));
        runJobs();
        assertThat(system(() -> jdbcTemplate.queryForList("select v.row_number, v.external_id, v.contract_number, "
                + "v.debtor_key from ingestion.import_row_value v join ingestion.import_batch b on b.id = v.batch_id "
                + "where b.review_id = ? and v.row_number in (2, 3, 4) order by v.row_number", review.id())))
                .hasSize(3).allSatisfy(r -> {
                    assertThat(r.get("external_id")).isNull();
                    assertThat(r.get("contract_number")).isNull();
                    assertThat(r.get("debtor_key")).isNull();
                });
        assertThat(system(() -> jdbcTemplate.queryForObject("select external_id from ingestion.import_row_value v "
                + "join ingestion.import_batch b on b.id = v.batch_id where b.review_id = ? and v.row_number = 5",
                String.class, review.id()))).isEqualTo("R-9");
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

    private List<ImportIssueView> subjectIssues(UUID batchId) {
        return issues(batchId).stream().filter(i -> DebtIngestionPort.SUBJECT_ANONYMIZED.equals(i.code())).toList();
    }
}
