package br.com.gestorcobranca.ingestion.infrastructure.pdf;

import br.com.gestorcobranca.ingestion.application.port.out.PdfDocumentReader.PdfInspection;
import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.pdf.CanonicalRecord;
import br.com.gestorcobranca.ingestion.domain.model.pdf.CheckReport;
import br.com.gestorcobranca.ingestion.domain.model.pdf.Extraction;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap;
import br.com.gestorcobranca.ingestion.domain.model.pdf.Finding;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfText;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TotalsCheck;
import br.com.gestorcobranca.ingestion.domain.service.pdf.CanonicalAssembler;
import br.com.gestorcobranca.ingestion.domain.service.pdf.ExtractionChecks;
import br.com.gestorcobranca.ingestion.domain.service.pdf.ExtractionMapValidator;
import br.com.gestorcobranca.ingestion.domain.service.pdf.LayoutFingerprint;
import br.com.gestorcobranca.ingestion.domain.service.pdf.MapInterpreter;
import br.com.gestorcobranca.ingestion.support.pdf.FakeDocuments;
import br.com.gestorcobranca.ingestion.support.pdf.PdfFixtures;
import br.com.gestorcobranca.ingestion.support.pdf.PdfFixtures.HistoryTitle;
import br.com.gestorcobranca.ingestion.support.pdf.PdfFixtures.ListDebtor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Leitura (PDFBox) + intérprete do mapa + montagem canônica + verificações, nos quatro layouts sintéticos.
 */
class PdfExtractionTest {

    private final PdfBoxDocumentReader reader = new PdfBoxDocumentReader();
    private final MapInterpreter interpreter = new MapInterpreter();
    private final CanonicalAssembler assembler = new CanonicalAssembler();
    private final ExtractionChecks checks = new ExtractionChecks(LocalDate.of(2026, 10, 30), 0.85);

    private record Result(PdfText text, Extraction extraction, List<CanonicalRecord> records, CheckReport report) {
    }

    private Result run(byte[] pdf, ExtractionMap map) {
        assertThat(new ExtractionMapValidator().validate(map)).isEmpty();
        PdfInspection inspection = reader.inspect(pdf, 50);
        assertThat(inspection.text().hasTextLayer()).isTrue();
        Extraction extraction = interpreter.interpret(inspection.text(), map);
        List<CanonicalRecord> records = assembler.assemble(extraction, map);
        CheckReport report = checks.check(inspection.text(), map, extraction, records, extraction.controls());
        return new Result(inspection.text(), extraction, records, report);
    }

    private static List<Finding> errors(CheckReport report) {
        return report.findings().stream().filter(f -> f.severity() == Finding.Severity.ERROR).toList();
    }

    @Test
    void historyWithTwoLineRecordsDebtorHeaderAndTotals() {
        List<HistoryTitle> titles = PdfFixtures.historyTitles(12);
        Result r = run(PdfFixtures.history(titles, null), PdfFixtures.historyMap());

        assertThat(r.extraction().debtors()).hasSize(1);
        assertThat(r.records()).hasSize(12);
        CanonicalRecord first = r.records().getFirst();
        assertThat(first.values()).containsEntry(CanonicalField.DEBTOR_NAME, "COMERCIO FICTICIO LTDA")
                .containsEntry(CanonicalField.DEBTOR_DOCUMENT, FakeDocuments.maskedCnpj(PdfFixtures.HISTORY_CNPJ))
                .containsEntry(CanonicalField.TITLE_EXTERNAL_ID, "1-32600-01")
                .containsEntry(CanonicalField.CONTRACT_NUMBER, "32600")
                .containsEntry(CanonicalField.DUE_DATE, titles.getFirst().due())
                .containsEntry(CanonicalField.PRINCIPAL_AMOUNT, "455.63")
                .containsEntry(CanonicalField.REPORTED_BALANCE, "455.63")
                .containsEntry(CanonicalField.BALANCE_REFERENCE_DATE, "27/10/2026")
                .containsEntry(CanonicalField.PHONE_1, "(11) 98765-4321")
                .containsEntry(CanonicalField.EMAIL_1, "contato@exemplo.test")
                .containsEntry(CanonicalField.ADDRESS_STREET, "RUA DAS FLORES FICTICIAS, 100")
                .doesNotContainKey(CanonicalField.TITLE_SITUATION);
        assertThat(first.typed()).contains(CanonicalField.PRINCIPAL_AMOUNT, CanonicalField.REPORTED_BALANCE);
        assertThat(first.info()).containsEntry("conta", "00047 - CLIENTES - VENDAS")
                .containsEntry("codigo_cliente", "024502").containsEntry("juros_mora", "R$ 75,35")
                .containsEntry("forma_pagamento", "033 - Boleto Exe");
        assertThat(first.lines()).hasSize(2);
        assertThat(r.records().get(1).values()).containsEntry(CanonicalField.TITLE_EXTERNAL_ID, "11-32600-A2")
                .containsEntry(CanonicalField.PRINCIPAL_AMOUNT, "1321.98");
        assertThat(r.records().get(2).values()).containsEntry(CanonicalField.REPORTED_BALANCE, "200.00");

        assertThat(r.report().totals()).hasSize(2).allMatch(TotalsCheck::matches);
        BigDecimal pending = titles.stream().map(HistoryTitle::pending).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(r.report().totals()).anySatisfy(t -> {
            assertThat(t.target()).isEqualTo("saldo");
            assertThat(t.document()).isEqualByComparingTo(pending);
            assertThat(t.records()).isEqualTo(12);
        });
        assertThat(r.report().blocking()).isFalse();
        assertThat(errors(r.report())).isEmpty();
        assertThat(r.extraction().unrecognized()).isEmpty();
    }

    @Test
    void recordSplitAcrossPagesKeepsItsSecondLine() {
        List<HistoryTitle> titles = PdfFixtures.historyTitles(12);
        Result r = run(PdfFixtures.history(titles, 5), PdfFixtures.historyMap());

        assertThat(r.text().pages()).hasSize(2);
        assertThat(r.records()).hasSize(12);
        CanonicalRecord split = r.records().get(5);
        assertThat(split.lines()).hasSize(2);
        assertThat(split.lines().get(0).page()).isEqualTo(1);
        assertThat(split.lines().get(1).page()).isEqualTo(2);
        assertThat(split.info()).containsEntry("conta", titles.get(5).account());
        assertThat(r.records().get(6).lines()).allMatch(l -> l.page() == 2);
        assertThat(r.report().totals()).allMatch(TotalsCheck::matches);
        assertThat(r.report().blocking()).isFalse();
        assertThat(r.extraction().unrecognized()).allMatch(n -> n.code().equals("pdf.line_page_preamble"));
    }

    @Test
    void severalDebtorsAndTableContinuingOnNextPage() {
        List<ListDebtor> debtors = PdfFixtures.listDebtors();
        Result r = run(PdfFixtures.debtorList(debtors), PdfFixtures.debtorListMap());

        assertThat(r.text().pages()).hasSize(2);
        assertThat(r.extraction().debtors()).hasSize(3);
        assertThat(r.records()).hasSize(73);
        assertThat(r.records().stream().filter(c -> c.debtorIndex() == 1)).hasSize(70);
        assertThat(r.records().stream().filter(c -> c.debtorIndex() == 1 && c.page() == 2)).isNotEmpty();
        CanonicalRecord last = r.records().getLast();
        assertThat(last.values()).containsEntry(CanonicalField.DEBTOR_NAME, "CARLOS TESTE PEREIRA")
                .containsEntry(CanonicalField.TITLE_EXTERNAL_ID, "CT-3003-1")
                .containsEntry(CanonicalField.PRINCIPAL_AMOUNT, "12345.67")
                .containsEntry(CanonicalField.REPORTED_BALANCE, "13000.00")
                .containsEntry(CanonicalField.BALANCE_REFERENCE_DATE, "30/10/2026");
        assertThat(r.report().totals()).hasSize(8).allMatch(TotalsCheck::matches);
        assertThat(r.report().totals()).anySatisfy(t -> {
            assertThat(t.target()).isEqualTo(ExtractionMap.COUNT_TARGET);
            assertThat(t.extracted()).isEqualByComparingTo("73");
        });
        assertThat(r.report().blocking()).isFalse();
        assertThat(errors(r.report())).isEmpty();
    }

    @Test
    void simpleOneLineTableWithDebtorColumns() {
        Result r = run(PdfFixtures.simpleTable(PdfFixtures.simpleTitles()), PdfFixtures.simpleMap());

        assertThat(r.records()).hasSize(3);
        assertThat(r.records().getFirst().values()).containsEntry(CanonicalField.DEBTOR_NAME, "MARIA DA SILVA FICTICIA")
                .containsEntry(CanonicalField.PRINCIPAL_AMOUNT, "1000.00")
                .containsEntry(CanonicalField.TITLE_EXTERNAL_ID, "9001-1");
        assertThat(r.records().get(1).values()).containsEntry(CanonicalField.PRINCIPAL_AMOUNT, "2.50");
        assertThat(r.records().get(2).values()).containsEntry(CanonicalField.PRINCIPAL_AMOUNT, "1234567.89");
        assertThat(r.report().totals()).hasSize(2).allMatch(TotalsCheck::matches);
        assertThat(errors(r.report())).isEmpty();
    }

    @Test
    void scannedPdfHasNoTextLayer() {
        PdfInspection inspection = reader.inspect(PdfFixtures.scanned(), 50);
        assertThat(inspection.pages()).isEqualTo(1);
        assertThat(inspection.text().hasTextLayer()).isFalse();
    }

    @Test
    void fingerprintRecognisesTheSameLayoutOnly() {
        PdfText history = reader.inspect(PdfFixtures.history(PdfFixtures.historyTitles(3), null), 50).text();
        PdfText simple = reader.inspect(PdfFixtures.simpleTable(PdfFixtures.simpleTitles()), 50).text();
        assertThat(LayoutFingerprint.matches(history, PdfFixtures.historyMap())).isTrue();
        assertThat(LayoutFingerprint.matches(simple, PdfFixtures.historyMap())).isFalse();
        assertThat(LayoutFingerprint.matches(simple, PdfFixtures.simpleMap())).isTrue();
        assertThat(LayoutFingerprint.of(PdfFixtures.historyMap())).hasSize(64)
                .isEqualTo(LayoutFingerprint.of(PdfFixtures.historyMap()))
                .isNotEqualTo(LayoutFingerprint.of(PdfFixtures.simpleMap()));
    }

    @Test
    void wrongLinesPerRecordIsVisibleInTheChecks() {
        ExtractionMap map = PdfFixtures.historyMap();
        ExtractionMap oneLine = new ExtractionMap(1, map.documentType(), map.numberFormat(), map.debtor(),
                map.documentFields(), new ExtractionMap.TableSection(1, List.of(map.table().headerLines().getFirst()),
                "Vencto", map.table().endMarkers(), map.table().columns().stream().filter(c -> c.line() == 1).toList()),
                map.controlTotals(), map.ignoreLinesContaining());
        Result r = run(PdfFixtures.history(PdfFixtures.historyTitles(4), null), oneLine);
        assertThat(r.records()).hasSize(4);
        // as 4 linhas "Conta" e a 2ª linha do cabeçalho, que o mapa não descreve
        assertThat(r.extraction().unrecognized()).hasSize(5).allMatch(n -> n.code().equals("pdf.line_outside_record"));
        assertThat(r.report().findings()).filteredOn(f -> f.code().equals("pdf.line_outside_record")).hasSize(5);
    }
}
