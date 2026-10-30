package br.com.gestorcobranca.ingestion.domain.service.pdf;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.pdf.CanonicalRecord;
import br.com.gestorcobranca.ingestion.domain.model.pdf.CheckReport;
import br.com.gestorcobranca.ingestion.domain.model.pdf.Extraction;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.ControlTotal;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.DebtorSection;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.DebtorSource;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.LabelField;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.NumberStyle;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.TableColumn;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.TableSection;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.TotalScope;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.ValuePosition;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.ValueTransform;
import br.com.gestorcobranca.ingestion.domain.model.pdf.Finding;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfText;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TextLine;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TextPage;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TextWord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Intérprete, montagem e verificações sobre texto posicionado montado à mão (sem PDF). */
class MapInterpreterTest {

    private static final double CHAR = 4.0;

    /** Linha com trechos {@code x:texto}; palavras do trecho separadas por um espaço de 1,5 pt. */
    private static TextLine line(int page, int number, String... cells) {
        List<TextWord> words = new ArrayList<>();
        double top = number * 10;
        for (String cell : cells) {
            int sep = cell.indexOf(':');
            double x = Double.parseDouble(cell.substring(0, sep));
            for (String w : cell.substring(sep + 1).split(" ")) {
                double width = w.length() * CHAR;
                words.add(new TextWord(w, x, x + width, top, top + 6));
                x += width + 1.5;
            }
        }
        return new TextLine(page, number, words);
    }

    private static PdfText text(TextLine... lines) {
        return new PdfText(List.of(new TextPage(1, 595, 842, List.of(lines))));
    }

    private static TableColumn col(String header, String... targets) {
        return new TableColumn(header, 1, List.of(targets), null, null);
    }

    private static ExtractionMap simpleMap(NumberStyle style, List<ControlTotal> controls) {
        return new ExtractionMap(1, "teste", style, new DebtorSection(DebtorSource.COLUMNS, null, List.of()), List.of(),
                new TableSection(1, List.of(List.of("Doc", "Nome", "Id", "Venc", "Valor")), "Venc", List.of("Total:"),
                        List.of(col("Doc", "documento"), col("Nome", "nome"), col("Id", "id_titulo"),
                                col("Venc", "vencimento"), col("Valor", "valor_principal"))), controls, List.of());
    }

    private static final String DOC = "529.982.247-25";

    private static PdfText simpleText(String amount1, String amount2, String total) {
        return text(line(1, 1, "10:Doc", "80:Nome", "200:Id", "260:Venc", "340:Valor"),
                line(1, 2, "10:" + DOC, "80:FULANO FICTICIO", "200:A1", "260:10/01/2026", "330:" + amount1),
                line(1, 3, "10:" + DOC, "80:FULANO FICTICIO", "200:A2", "260:10/02/2026", "330:" + amount2),
                line(1, 4, "10:Total:", "330:" + total));
    }

    private record Run(Extraction extraction, List<CanonicalRecord> records, CheckReport report) {
    }

    private static Run run(PdfText text, ExtractionMap map) {
        Extraction e = new MapInterpreter().interpret(text, map);
        List<CanonicalRecord> records = new CanonicalAssembler().assemble(e, map);
        CheckReport report = new ExtractionChecks(LocalDate.of(2026, 10, 30), 0.85).check(text, map, e, records, e.controls());
        return new Run(e, records, report);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "COMMA|1.234,56|10,00|1.244,56|1234.56|10.00",
            "COMMA|R$ 1.000|R$ 0,50|R$ 1.000,50|1000.00|0.50",
            "DOT|1,234.56|10.00|1,244.56|1234.56|10.00",
            "COMMA|1.000.000,00|2,5|1.000.002,50|1000000.00|2.50"})
    void readsAmountsWithTheMapSeparator(String style, String a1, String a2, String total, String c1, String c2) {
        ExtractionMap map = simpleMap(NumberStyle.valueOf(style),
                List.of(new ControlTotal("Total:", null, "valor_principal", TotalScope.DOCUMENT)));
        Run r = run(simpleText(a1, a2, total), map);
        assertThat(r.records()).extracting(c -> c.values().get(CanonicalField.PRINCIPAL_AMOUNT)).containsExactly(c1, c2);
        assertThat(r.report().totalsMatch()).isTrue();
        assertThat(r.report().blocking()).isFalse();
    }

    @Test
    void totalsMismatchBlocks() {
        ExtractionMap map = simpleMap(NumberStyle.COMMA,
                List.of(new ControlTotal("Total:", null, "valor_principal", TotalScope.DOCUMENT)));
        Run r = run(simpleText("100,00", "50,00", "1.150,00"), map);
        assertThat(r.report().totalsMatch()).isFalse();
        assertThat(r.report().blocking()).isTrue();
        assertThat(r.report().findings()).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("pdf.totals_mismatch");
            assertThat(f.blocking()).isTrue();
            assertThat(f.message()).contains("R$ 1.150,00").contains("R$ 150,00");
        });
        assertThat(r.report().totals().getFirst().difference()).isEqualByComparingTo("1000.00");
    }

    @Test
    void separatorReadWithTheWrongStyleIsCaughtByTheTotals() {
        // documento em pt-BR lido como se fosse ponto decimal: "1.234,56" não é válido; "10,00" vira 1000
        ExtractionMap map = simpleMap(NumberStyle.DOT,
                List.of(new ControlTotal("Total:", null, "valor_principal", TotalScope.DOCUMENT)));
        Run r = run(simpleText("1.234,56", "10,00", "1.244,56"), map);
        assertThat(r.report().blocking()).isTrue();
    }

    @Test
    void unreadableControlBlocksAndMissingControlWarns() {
        ExtractionMap map = simpleMap(NumberStyle.COMMA, List.of(
                new ControlTotal("Total:", null, "valor_principal", TotalScope.DOCUMENT),
                new ControlTotal("Soma geral:", null, "valor_principal", TotalScope.DOCUMENT)));
        Run r = run(simpleText("1,00", "2,00", "abc"), map);
        assertThat(r.report().findings()).extracting(Finding::code)
                .contains("pdf.control_unreadable", "pdf.control_not_found");
        assertThat(r.report().blocking()).isTrue();
    }

    @Test
    void lineWithInvalidAnchorDateIsReportedNotSilentlyLost() {
        PdfText text = text(line(1, 1, "10:Doc", "80:Nome", "200:Id", "260:Venc", "340:Valor"),
                line(1, 2, "10:111.111.111-11", "80:FULANO", "200:A1", "260:10/01/2026", "330:1,00"),
                line(1, 3, "10:" + DOC, "80:FULANO", "200:A1", "260:31/02/2026", "330:2,00"));
        Run r = run(text, simpleMap(NumberStyle.COMMA, List.of()));
        assertThat(r.records()).hasSize(1);
        // vencimento inválido na âncora: a linha não inicia título e aparece como linha não reconhecida
        assertThat(r.report().findings()).extracting(Finding::code)
                .contains("pdf.line_outside_record", "pdf.no_control_totals");
    }

    @Test
    void ingestionRowValidationAppearsPerRecord() {
        PdfText text = text(line(1, 1, "10:Doc", "80:Nome", "200:Id", "260:Venc", "340:Valor"),
                line(1, 2, "10:111.111.111-11", "80:FULANO", "200:A1", "260:10/01/2026", "330:1,00"),
                line(1, 3, "10:" + DOC, "80:FULANO", "200:A1", "260:10/02/2026", "330:2,00"));
        Run r = run(text, simpleMap(NumberStyle.COMMA, List.of()));
        assertThat(r.records()).hasSize(2);
        assertThat(r.report().findings()).filteredOn(f -> f.record() != null && f.record() == 1)
                .extracting(Finding::field).contains("documento");
        assertThat(r.report().findings()).filteredOn(f -> f.record() != null && f.record() == 2)
                .extracting(Finding::code).contains("row.duplicate_title");
        assertThat(r.report().blocking()).isFalse();
    }

    @Test
    void labelValuesRightAndBelowWithTransforms() {
        ExtractionMap map = new ExtractionMap(1, "t", NumberStyle.COMMA,
                new DebtorSection(DebtorSource.HEADER, "Cliente:", List.of(
                        new LabelField("Cliente:", "nome", ValuePosition.RIGHT, ValueTransform.DROP_LEADING_CODE),
                        new LabelField("Cliente:", "info:codigo", ValuePosition.RIGHT, ValueTransform.LEADING_CODE),
                        new LabelField("CPF/CNPJ:", "documento", ValuePosition.RIGHT, ValueTransform.NONE),
                        new LabelField("Telefone", "telefone_1", ValuePosition.BELOW, ValueTransform.NONE))),
                List.of(new LabelField("Data base:", "data_saldo", null, null)),
                new TableSection(1, List.of(List.of("Id", "Venc", "Valor")), "Venc", List.of(),
                        List.of(col("Id", "id_titulo"), col("Venc", "vencimento"), col("Valor", "valor_principal"))),
                List.of(), List.of());
        PdfText text = text(line(1, 1, "10:Data base: 01/10/2026", "300:Hora: 10:00"),
                line(1, 2, "10:Cliente: 0123 EMPRESA FICTICIA SA", "300:CPF/CNPJ: " + DOC),
                line(1, 3, "300:Telefone"),
                line(1, 4, "300:(11) 3333-4444"),
                line(1, 5, "10:Id", "100:Venc", "200:Valor"),
                line(1, 6, "10:X1", "100:10/10/2026", "200:5,00"));
        Run r = run(text, map);
        assertThat(r.records()).hasSize(1);
        assertThat(r.records().getFirst().values())
                .containsEntry(CanonicalField.DEBTOR_NAME, "EMPRESA FICTICIA SA")
                .containsEntry(CanonicalField.DEBTOR_DOCUMENT, DOC)
                .containsEntry(CanonicalField.PHONE_1, "(11) 3333-4444")
                .containsEntry(CanonicalField.BALANCE_REFERENCE_DATE, "01/10/2026");
        assertThat(r.records().getFirst().info()).containsEntry("codigo", "0123");
        assertThat(r.report().findings()).noneMatch(f -> f.code().equals("pdf.value_not_in_text"));
    }

    @Test
    void ignoredLinesAndPaidColumn() {
        ExtractionMap base = simpleMap(NumberStyle.COMMA, List.of());
        ExtractionMap map = new ExtractionMap(1, "t", NumberStyle.COMMA, base.debtor(), List.of(),
                new TableSection(1, List.of(List.of("Doc", "Nome", "Id", "Venc", "Valor", "Pago")), "Venc", List.of(),
                        List.of(col("Doc", "documento"), col("Nome", "nome"), col("Id", "id_titulo"),
                                col("Venc", "vencimento"), col("Valor", "valor_principal"),
                                col("Pago", ExtractionMap.PAID_WHEN_FILLED))), List.of(), List.of("Página"));
        PdfText text = text(line(1, 1, "10:Doc", "80:Nome", "200:Id", "260:Venc", "340:Valor", "420:Pago"),
                line(1, 2, "10:" + DOC, "80:FULANO", "200:A1", "260:10/01/2026", "330:1,00", "420:12/01/2026"),
                line(1, 3, "200:Página 1 de 1"),
                line(1, 4, "10:" + DOC, "80:FULANO", "200:A2", "260:10/02/2026", "330:2,00"));
        Run r = run(text, map);
        assertThat(r.records()).hasSize(2);
        assertThat(r.records().get(0).values()).containsEntry(CanonicalField.TITLE_SITUATION, "PAGO");
        assertThat(r.records().get(1).values()).doesNotContainKey(CanonicalField.TITLE_SITUATION);
        assertThat(r.extraction().unrecognized()).isEmpty();
    }

    @Test
    void sameInputSameOutput() {
        ExtractionMap map = simpleMap(NumberStyle.COMMA, List.of());
        PdfText text = simpleText("1,00", "2,00", "3,00");
        assertThat(run(text, map).records()).isEqualTo(run(text, map).records());
    }

    @Test
    void validatorRejectsUnknownTargetsAndMissingRequiredFields() {
        ExtractionMap map = new ExtractionMap(1, "t", NumberStyle.COMMA,
                new DebtorSection(DebtorSource.COLUMNS, null, List.of()), List.of(),
                new TableSection(2, List.of(List.of("A", "B")), null, List.of(),
                        List.of(col("A", "campo_inexistente"), col("C", "nome"))),
                List.of(new ControlTotal("Total:", "Z", "nome")), List.of("a"));
        assertThat(new ExtractionMapValidator().validate(map)).extracting(p -> p.code())
                .contains("pdf_map.target_invalid", "pdf_map.column_not_in_header", "pdf_map.anchor_invalid",
                        "pdf_map.control_column", "pdf_map.control_target", "pdf_map.ignore_text_short",
                        "pdf_map.required_missing");
    }
}
