package br.com.gestorcobranca.ingestion;

import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportBatchView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportIssueView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportRecordsCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ReprocessCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportLayoutUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.ImportLayoutUseCases.ColumnCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportLayoutUseCases.ImportLayoutView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportLayoutUseCases.PublishLayoutCommand;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.debt.PortfolioLoaded;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Fluxos da ingestão contra PostgreSQL real: carga CSV/XLSX/API, validação e relatório de erros,
 * reimportação idempotente, pré-visualização, situações informadas pelo credor, escopo por
 * credor, cancelamento e recusa de arquivos maliciosos.
 */
@RecordApplicationEvents
class IngestionModuleIT extends IngestionTestSupport {

    private static final String MARIA = "529.982.247-25";
    private static final String JOAO = "111.444.777-35";

    @Autowired
    ApplicationEvents events;
    @Autowired
    ImportLayoutUseCases layouts;
    @Autowired
    br.com.gestorcobranca.debt.application.port.in.OriginContractUseCases contractsQuery;
    @Autowired
    br.com.gestorcobranca.debt.application.port.in.ObligationPartyUseCases parties;
    @Autowired
    br.com.gestorcobranca.debt.application.port.in.CollateralUseCases collaterals;

    private static String file(String... lines) {
        return HEADER + "\n" + String.join("\n", lines) + "\n";
    }

    private List<PortfolioLoaded> loaded(UUID batchId) {
        return events.stream(PortfolioLoaded.class).filter(e -> e.importBatchId().equals(batchId)).toList();
    }

    @Test
    void csvImportLoadsPortfolioReportsRowErrorsAndReimportIsIdempotent() {
        String content = file(
                line(MARIA, "Maria Silva", "T-1", "1.000,00", null),
                line(JOAO, "João Souza", "T-2", "250,50", ""),
                line(MARIA, "Maria Silva", "T-3", "300", "ativo"),
                line("529.982.247-24", "Documento Errado", "T-4", "10", null),
                line(MARIA, "Maria Silva", "T-1", "1.000,00", null));
        ImportBatchView received = uploadCsv("carteira.csv", content, null);
        assertThat(received.status()).isEqualTo("RECEIVED");
        assertThat(received.fileSha256()).hasSize(64);

        runJobs();
        ImportBatchView done = batch(received.id());
        assertThat(done.status()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(done.totalRows()).isEqualTo(5);
        assertThat(done.progressPercent()).isEqualTo(100);
        assertThat(done.counters().rowsRead()).isEqualTo(5);
        assertThat(done.counters().rowsValid()).isEqualTo(3);
        assertThat(done.counters().rowsWithError()).isEqualTo(2);
        assertThat(done.counters().titlesCreated()).isEqualTo(3);
        assertThat(done.counters().debtorsCreated()).isEqualTo(2);
        assertThat(done.counters().principalTotal()).isEqualByComparingTo("1550.50");
        assertThat(titleCount(creditorA)).isEqualTo(3);
        assertThat(loaded(done.id())).singleElement().satisfies(e -> {
            assertThat(e.titlesCreated()).isEqualTo(3);
            assertThat(e.debtorsAffected()).isEqualTo(2);
            assertThat(e.portfolioId()).isEqualTo(portfolioA);
        });
        List<ImportIssueView> issues = issues(done.id());
        assertThat(issues).extracting(ImportIssueView::rowNumber, ImportIssueView::code)
                .contains(tuple(5L, "row.document_invalid"), tuple(6L, "row.duplicate_title"));
        ImportIssueView document = issues.stream().filter(i -> i.code().equals("row.document_invalid")).findFirst().orElseThrow();
        assertThat(document.valueExcerpt()).isEqualTo("***.***.***-24");

        // mesmo arquivo: recusado sem confirmação; com motivo, reprocessado sem duplicar nada
        assertThatThrownBy(() -> uploadCsv("carteira.csv", content, null)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "import.duplicate_file");
        assertThatThrownBy(() -> uploadCsv("carteira.csv", content, null, true, " "))
                .isInstanceOf(BusinessRuleException.class);
        ImportBatchView forced = uploadCsv("carteira.csv", content, null, true, "Reenvio solicitado pelo credor");
        assertThat(forced.forced()).isTrue();
        assertThat(forced.duplicateOfBatchId()).isEqualTo(done.id());
        runJobs();
        ImportBatchView again = batch(forced.id());
        assertThat(again.status()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(again.counters().titlesCreated()).isZero();
        assertThat(again.counters().titlesUnchanged()).isEqualTo(3);
        assertThat(again.counters().debtorsCreated()).isZero();
        assertThat(titleCount(creditorA)).isEqualTo(3);
        assertThat(loaded(forced.id())).isEmpty();

        // carga seguinte corrige o principal de um título
        ImportBatchView update = uploadCsv("carteira-2.csv", file(line(MARIA, "Maria Silva", "T-1", "1.100,00", null)), null);
        runJobs();
        assertThat(batch(update.id()).counters().titlesUpdated()).isEqualTo(1);
        assertThat(title(creditorA, "T-1").principalAmount()).isEqualByComparingTo("1100.00");
        assertThat(loaded(update.id())).singleElement().satisfies(e -> assertThat(e.titlesUpdated()).isEqualTo(1));
    }

    @Test
    void previewValidatesWithoutWritingAndCanBeImportedAfterwards() {
        String content = file(line(MARIA, "Maria Silva", "P-1", "10", null), line(JOAO, "João", "P-2", "abc", null));
        ImportBatchView preview = uploadCsv("previa.csv", content, "VALIDATE_ONLY");
        runJobs();
        ImportBatchView validated = batch(preview.id());
        assertThat(validated.status()).isEqualTo("VALIDATED");
        assertThat(validated.counters().rowsValid()).isEqualTo(1);
        assertThat(validated.counters().rowsWithError()).isEqualTo(1);
        assertThat(validated.counters().titlesCreated()).isZero();
        assertThat(titleCount(creditorA)).isZero();
        assertThat(loaded(preview.id())).isEmpty();

        ImportBatchView imported = asImporterOf(creditorA, () -> batches.reprocess(preview.id(),
                new ReprocessCommand("IMPORT", null)));
        assertThat(imported.reprocessOfBatchId()).isEqualTo(preview.id());
        runJobs();
        assertThat(batch(imported.id()).status()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(titleCount(creditorA)).isEqualTo(1);

        // reprocessar de novo um arquivo já importado exige motivo
        assertThatThrownBy(() -> asImporterOf(creditorA, () -> batches.reprocess(imported.id(), null)))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "import.duplicate_file");
    }

    @Test
    void creditorSituationsSuspendOrSkipTitlesWithoutFinancialEffects() {
        ImportBatchView first = uploadCsv("situacoes.csv", file(
                line(MARIA, "Maria Silva", "S-1", "100", "ATIVO"),
                line(MARIA, "Maria Silva", "S-2", "100", "Suspenso"),
                line(JOAO, "João Souza", "S-3", "100", "contestado"),
                line(JOAO, "João Souza", "S-9", "100", "PAGO")), null);
        runJobs();
        ImportBatchView done = batch(first.id());
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.counters().titlesCreated()).isEqualTo(3);
        assertThat(done.counters().titlesSkipped()).isEqualTo(1);
        assertThat(done.counters().titlesSuspended()).isEqualTo(2);
        assertThat(done.counters().principalTotal()).isEqualByComparingTo("300.00");
        assertThat(title(creditorA, "S-1").status()).isEqualTo("OPEN");
        assertThat(title(creditorA, "S-2").status()).isEqualTo("SUSPENDED");
        assertThat(title(creditorA, "S-3").status()).isEqualTo("SUSPENDED");
        assertThat(allTitles(creditorA)).extracting(t -> t.externalId()).doesNotContain("S-9");
        assertThat(issues(done.id())).extracting(ImportIssueView::code)
                .contains("row.title_closed_not_found", "row.title_suspended_by_creditor", "row.title_disputed_by_creditor");

        ImportBatchView second = uploadCsv("situacoes-2.csv", file(line(MARIA, "Maria Silva", "S-1", "999", "pago")), null);
        runJobs();
        ImportBatchView paid = batch(second.id());
        assertThat(paid.counters().titlesSkipped()).isEqualTo(1);
        assertThat(paid.counters().titlesSuspended()).isEqualTo(1);
        assertThat(title(creditorA, "S-1").status()).isEqualTo("SUSPENDED");
        // pago informado não altera o principal nem dá baixa: só suspende para conciliação
        assertThat(title(creditorA, "S-1").principalAmount()).isEqualByComparingTo("100.00");
        assertThat(issues(paid.id())).extracting(ImportIssueView::code).containsExactly("row.title_reported_paid");
    }

    @Test
    void xlsxWithCreditorLayoutWarnsAboutIgnoredAndFutureColumns() throws IOException {
        ImportLayoutView layout = asImporterOf(creditorA, () -> layouts.publish(new PublishLayoutCommand(creditorA,
                portfolioA, "banco_a", "Banco A — padrão", "XLSX", null, null, true, "Titulos", List.of(
                new ColumnCommand("documento", "CPF/CNPJ", null),
                new ColumnCommand("nome", "Nome do Cliente", null),
                new ColumnCommand("id_titulo", "Nosso Número", null),
                new ColumnCommand("vencimento", "Vencimento", null),
                new ColumnCommand("valor_principal", "Valor Original", null)))));
        assertThat(layout.version()).isEqualTo(1);
        assertThat(layout.portfolioId()).isEqualTo(portfolioA);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Capa");
            Sheet sheet = workbook.createSheet("Titulos");
            String[] header = {"CPF/CNPJ", "Nome do Cliente", "Nosso Número", "Vencimento", "Valor Original",
                    "Garantia Tipo", "Observação"};
            Row head = sheet.createRow(0);
            for (int i = 0; i < header.length; i++) {
                head.createCell(i).setCellValue(header[i]);
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue(MARIA);
            row.createCell(1).setCellValue("Maria Silva");
            row.createCell(2).setCellValue("X-1");
            row.createCell(3).setCellValue("10/03/2026");
            row.createCell(4).setCellValue(1234.5);
            row.createCell(5).setCellValue("VEICULO");
            workbook.write(out);
        }
        ImportBatchView received = upload(portfolioA, layout.id(), "titulos.xlsx", out.toByteArray(), null, false, null);
        assertThat(received.layoutCode()).isEqualTo("BANCO_A");
        assertThat(received.source()).isEqualTo("XLSX");
        runJobs();
        ImportBatchView done = batch(received.id());
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.counters().warnings()).isEqualTo(2);
        assertThat(title(creditorA, "X-1").principalAmount()).isEqualByComparingTo("1234.50");
        assertThat(issues(done.id())).extracting(ImportIssueView::rowNumber, ImportIssueView::code)
                .containsExactlyInAnyOrder(tuple(0L, "import.columns_not_supported"), tuple(0L, "import.columns_ignored"));

        // layout restrito à carteira A não serve à carteira B; arquivo CSV não serve a layout XLSX
        UUID otherPortfolio = portfolio(creditorA, "A-02");
        assertThatThrownBy(() -> upload(otherPortfolio, layout.id(), "titulos.xlsx", out.toByteArray(), null, false, null))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "import.layout_not_applicable");
        byte[] csv = file(line(MARIA, "Maria", "X-2", "1", null)).getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> upload(portfolioA, layout.id(), "titulos.csv", csv, null, false, null))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "import.format_mismatch");

        // nova versão desativa a anterior
        ImportLayoutView v2 = asImporterOf(creditorA, () -> layouts.publish(new PublishLayoutCommand(creditorA, null,
                "BANCO_A", "Banco A v2", "CSV", ";", "UTF-8", true, null, List.of(
                new ColumnCommand("documento", "doc", null), new ColumnCommand("nome", "nome", null),
                new ColumnCommand("id_titulo", "id", null), new ColumnCommand("vencimento", "venc", null),
                new ColumnCommand("valor_principal", "valor", null)))));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(asImporterOf(creditorA, () -> layouts.get(layout.id())).active()).isFalse();
    }

    @Test
    void legalFieldsRegisterNatureCoObligorsAndCollateralIdempotently() {
        String header = HEADER + ";natureza_titulo;titulo_executivo;coobrigado_1_documento;coobrigado_1_nome;"
                + "coobrigado_1_papel;garantia_id;garantia_tipo;garantia_placa;garantia_ano_fabricacao;garantia_ano_modelo;"
                + "avaliacao_valor;avaliacao_data;avaliacao_fonte";
        String legal = ";CCB;S;" + JOAO + ";João Souza;Avalista;G-1;Alienação fiduciária veículo;ABC1D23;2020;2021;"
                + "50.000,00;01/10/2026;FIPE";
        String content = header + "\n"
                + line(MARIA, "Maria Silva", "L-1", "10.000,00", null) + legal + "\n"
                + line(MARIA, "Maria Silva", "L-2", "500,00", null) + ";;;" + JOAO + ";João Souza;sócio;;;;;;;;\n";
        ImportBatchView received = uploadCsv("juridico.csv", content, null);
        runJobs();
        ImportBatchView done = batch(received.id());
        assertThat(done.status()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(issues(done.id())).extracting(ImportIssueView::rowNumber, ImportIssueView::code)
                .containsExactly(tuple(3L, "row.co_obligor_role_invalid"));
        assertThat(titleCount(creditorA)).isEqualTo(1);

        UUID contractId = title(creditorA, "L-1").originContractId();
        assertThat(system(() -> contractsQuery.get(contractId))).satisfies(c -> {
            assertThat(c.instrumentType()).isEqualTo("CCB");
            assertThat(c.executiveTitle()).isTrue();
        });
        // a lista inclui o devedor principal do contrato
        assertThat(system(() -> parties.list(contractId))).extracting(p -> p.role(), p -> p.active())
                .containsExactlyInAnyOrder(tuple("PRINCIPAL", true), tuple("GUARANTOR_AVAL", true));
        var collateral = system(() -> collaterals.listByContract(contractId));
        assertThat(collateral).singleElement().satisfies(c -> {
            assertThat(c.type()).isEqualTo("VEHICLE_FIDUCIARY_ALIENATION");
            assertThat(c.externalId()).isEqualTo("G-1");
            assertThat(c.estimatedValue()).isEqualByComparingTo("50000.00");
        });

        // reimportação: nada é duplicado (contrato, coobrigado, garantia e avaliação)
        ImportBatchView again = uploadCsv("juridico.csv", content, null, true, "Reenvio para teste de idempotência");
        runJobs();
        assertThat(batch(again.id()).counters().titlesUnchanged()).isEqualTo(1);
        assertThat(system(() -> parties.list(contractId))).hasSize(2);
        assertThat(system(() -> collaterals.listByContract(contractId))).hasSize(1);
        assertThat(system(() -> collaterals.appraisals(collateral.getFirst().id()))).hasSize(1);
    }

    @Test
    void apiRecordsAreIdempotentByKey() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("documento", MARIA);
        record.put("nome", "Maria Silva");
        record.put("id_titulo", "API-1");
        record.put("vencimento", "2026-03-10");
        record.put("valor_principal", "150.00");
        record.put("coobrigado_3_documento", JOAO);
        ImportRecordsCommand command = new ImportRecordsCommand(portfolioA, null, false, null, List.of(record));
        ImportBatchView first = asImporterOf(creditorA, () -> batches.importRecords(command, "erp-2026-10-23-001"));
        ImportBatchView repeated = asImporterOf(creditorA, () -> batches.importRecords(command, "erp-2026-10-23-001"));
        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(first.source()).isEqualTo("API");

        Map<String, Object> changed = new LinkedHashMap<>(record);
        changed.put("valor_principal", "200.00");
        assertThatThrownBy(() -> asImporterOf(creditorA, () -> batches.importRecords(
                new ImportRecordsCommand(portfolioA, null, false, null, List.of(changed)), "erp-2026-10-23-001")))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "idempotency.key_reused");
        assertThatThrownBy(() -> asImporterOf(creditorA, () -> batches.importRecords(command, " ")))
                .isInstanceOf(BusinessRuleException.class);

        runJobs();
        ImportBatchView done = batch(first.id());
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.counters().titlesCreated()).isEqualTo(1);
        assertThat(issues(done.id())).extracting(ImportIssueView::code).containsExactly("import.columns_not_supported");
        assertThat(issues(done.id()).getFirst().message()).doesNotContain(JOAO);
    }

    @Test
    void issueReportIsMaskedAndSafeForSpreadsheets() {
        String content = HEADER + ";produto\n"
                + line(MARIA, "Maria Silva", "R-1", "10", null) + ";=1+1\n"
                + line("529.982.247-24", "Maria Silva", "R-2", "10", null) + ";Cartão\n";
        ImportBatchView received = uploadCsv("relatorio.csv", content, null);
        runJobs();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        asImporterOf(creditorA, () -> {
            batches.exportIssues(received.id(), out);
            return null;
        });
        String report = out.toString(StandardCharsets.UTF_8);
        assertThat(report).contains("row.formula_not_allowed").contains("'=1+1").contains("***.***.***-24")
                .doesNotContain("529.982.247-24").doesNotContain("Maria Silva");
        assertThat(count("select count(*) from shared.access_log where tenant_id = ? and event_type = 'DATA_EXPORTED'",
                tenant)).isEqualTo(1);
    }

    @Test
    void creditorScopeIsEnforced() {
        ImportBatchView received = uploadCsv("escopo.csv", file(line(MARIA, "Maria", "E-1", "10", null)), null);
        assertThatThrownBy(() -> asImporterOf(creditorB, () -> batches.get(received.id())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(asImporterOf(creditorB, () -> batches.search(null, br.com.gestorcobranca.shared.paging.PageQuery.of(0, 20)))
                .content()).isEmpty();
        byte[] bytes = file(line(JOAO, "João", "E-2", "10", null)).getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> asImporterOf(creditorB, () -> batches.uploadFile(
                new br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.UploadFileCommand(portfolioA,
                        null, null, false, null, "x.csv", "text/csv", bytes.length,
                        () -> new java.io.ByteArrayInputStream(bytes)))))
                .isInstanceOfAny(ResourceNotFoundException.class, ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> asImporterOf(creditorB, () -> batches.cancel(received.id(), "x")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void queuedBatchIsCancelledImmediately() {
        ImportBatchView received = uploadCsv("cancelar.csv", file(line(MARIA, "Maria", "C-1", "10", null)), null);
        ImportBatchView cancelled = asImporterOf(creditorA, () -> batches.cancel(received.id(), "Arquivo errado"));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.statusReason()).isEqualTo("Arquivo errado");
        assertThat(runJobs()).isZero();
        assertThat(titleCount(creditorA)).isZero();
        assertThatThrownBy(() -> asImporterOf(creditorA, () -> batches.cancel(received.id(), "de novo")))
                .isInstanceOf(ConflictException.class);
        // o mesmo arquivo pode ser enviado de novo (o cancelado não conta como importado)
        assertThat(uploadCsv("cancelar.csv", file(line(MARIA, "Maria", "C-1", "10", null)), null).status())
                .isEqualTo("RECEIVED");
    }

    @Test
    void maliciousOrInvalidFilesAreRejected() {
        byte[] csv = file(line(MARIA, "Maria", "M-1", "10", null)).getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> upload(portfolioA, null, "planilha.xlsx", csv, null, false, null))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "import.file_type_invalid");
        assertThatThrownBy(() -> upload(portfolioA, null, "programa.exe", csv, null, false, null))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "import.file_type_invalid");
        assertThatThrownBy(() -> asImporterOf(creditorA, () -> batches.uploadFile(
                new br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.UploadFileCommand(portfolioA,
                        null, null, false, null, "grande.csv", "text/csv", 60L * 1024 * 1024,
                        () -> new java.io.ByteArrayInputStream(csv)))))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "import.file_too_large");

        // CSV estruturalmente inválido: falha na leitura prévia, antes de gravar qualquer linha
        ImportBatchView broken = uploadCsv("quebrado.csv", HEADER + "\n" + line(MARIA, "Maria", "Q-1", "10", null)
                + "\n\"aspas;sem;fechar\n", null);
        runJobs();
        ImportBatchView failed = batch(broken.id());
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(issues(broken.id())).extracting(ImportIssueView::rowNumber, ImportIssueView::code)
                .containsExactly(tuple(0L, "import.csv_malformed"));
        assertThat(titleCount(creditorA)).isZero();
    }

    @Test
    void historyTablesAreAppendOnly() {
        ImportBatchView received = uploadCsv("historico.csv", file(line("529.982.247-24", "Maria", "H-1", "10", null)), null);
        runJobs();
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update ingestion.import_row_issue set message = 'x' where batch_id = ?", received.id())))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "delete from ingestion.import_batch where id = ?", received.id())))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void canonicalLayoutTemplateIsAvailable() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        asImporterOf(creditorA, () -> {
            layouts.writeTemplate("CSV", out);
            return null;
        });
        assertThat(out.toString(StandardCharsets.UTF_8)).startsWith("﻿documento;nome;tipo_pessoa").contains("situacao");
        assertThat(asImporterOf(creditorA, layouts::canonicalLayout).reservedPrefixes()).contains("garantia");
    }
}
