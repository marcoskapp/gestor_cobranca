package br.com.gestorcobranca.ingestion.infrastructure.parsing;

import br.com.gestorcobranca.ingestion.application.IngestionProperties;
import br.com.gestorcobranca.ingestion.application.port.out.ImportRecordReader.RecordSink;
import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.FileLayout;
import br.com.gestorcobranca.ingestion.domain.model.ImportFileException;
import br.com.gestorcobranca.ingestion.domain.model.ImportFormat;
import br.com.gestorcobranca.ingestion.domain.model.SourceRow;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordReadersTest {

    private static final String HEADER = "documento;nome;id_titulo;vencimento;valor_principal";
    private final FormatImportRecordReader reader = new FormatImportRecordReader(new JsonCodec(JsonMapper.builder().build()),
            IngestionProperties.defaults());

    private static final class Collector implements RecordSink {
        final List<SourceRow> rows = new ArrayList<>();
        List<String> ignored;

        @Override
        public void row(SourceRow row) {
            rows.add(row);
        }

        @Override
        public void ignoredColumns(List<String> columns) {
            ignored = columns;
        }
    }

    private Collector read(byte[] content, FileLayout layout) {
        Collector collector = new Collector();
        reader.read(new ByteArrayInputStream(content), layout, collector);
        return collector;
    }

    @Test
    void readsCsvWithBomQuotesBlankLinesAndExtraColumns() {
        String csv = "﻿" + HEADER + ";observacao\n"
                + "529.982.247-25;\"Silva; Maria\";T-1;10/03/2026;\"1.234,56\";x\n"
                + "\n"
                + ";;;;;\n"
                + "11144477735;João;T-2;2026-03-10;10;\n";
        Collector result = read(csv.getBytes(StandardCharsets.UTF_8), FileLayout.standard(ImportFormat.CSV));
        assertThat(result.rows).extracting(SourceRow::rowNumber).containsExactly(2L, 5L);
        assertThat(result.rows.getFirst().get(CanonicalField.DEBTOR_NAME)).contains("Silva; Maria");
        assertThat(result.ignored).containsExactly("observacao");
    }

    @Test
    void refusesCharactersInvalidForTheLayoutEncoding() {
        byte[] latin1 = (HEADER + "\n52998224725;José;T-1;10/03/2026;10\n").getBytes(Charset.forName("ISO-8859-1"));
        assertThatThrownBy(() -> read(latin1, FileLayout.standard(ImportFormat.CSV)))
                .isInstanceOf(ImportFileException.class).hasFieldOrPropertyWithValue("code", "import.encoding_invalid");
    }

    @Test
    void reportsMalformedCsvAndMissingHeader() {
        assertThatThrownBy(() -> read((HEADER + "\n1;\"aberta;T;1;1\n").getBytes(StandardCharsets.UTF_8),
                FileLayout.standard(ImportFormat.CSV))).isInstanceOf(ImportFileException.class)
                .hasFieldOrPropertyWithValue("code", "import.csv_malformed");
        assertThatThrownBy(() -> read("\n\n".getBytes(StandardCharsets.UTF_8), FileLayout.standard(ImportFormat.CSV)))
                .isInstanceOf(ImportFileException.class).hasFieldOrPropertyWithValue("code", "import.header_missing");
    }

    @Test
    void readsXlsxCellsIndependentlyOfFormattingAndUsesCachedFormulaValues() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("dados");
            Row header = sheet.createRow(0);
            String[] columns = HEADER.split(";");
            for (int i = 0; i < columns.length; i++) {
                header.createCell(i).setCellValue(columns[i]);
            }
            header.createCell(5).setCellValue("garantia_2_tipo");
            Row row = sheet.createRow(2);
            row.createCell(0).setCellValue(52998224725d);
            row.createCell(1).setCellValue("Maria");
            row.createCell(2).setCellValue("T-1");
            row.createCell(3).setCellValue(java.time.LocalDate.of(2026, 3, 10));
            row.getCell(3).setCellStyle(dateStyle(workbook));
            row.createCell(4).setCellFormula("0.1+0.2");
            workbook.setForceFormulaRecalculation(false);
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.write(out);
        }
        Collector result = read(out.toByteArray(), FileLayout.standard(ImportFormat.XLSX));
        assertThat(result.rows).singleElement().satisfies(r -> {
            assertThat(r.rowNumber()).isEqualTo(3);
            assertThat(r.get(CanonicalField.DEBTOR_DOCUMENT)).contains("52998224725");
            assertThat(r.get(CanonicalField.DUE_DATE)).contains("2026-03-10");
            assertThat(r.get(CanonicalField.PRINCIPAL_AMOUNT)).contains("0.3");
            assertThat(r.isTyped(CanonicalField.PRINCIPAL_AMOUNT)).isTrue();
            assertThat(r.isTyped(CanonicalField.DEBTOR_NAME)).isFalse();
            assertThat(r.isTyped(CanonicalField.DUE_DATE)).isFalse();
        });
        assertThat(result.ignored).containsExactly("garantia_2_tipo");
    }

    @Test
    void xlsxNumericCellsAreTypedAndTextNumbersStayText() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("dados");
            Row header = sheet.createRow(0);
            String[] columns = (HEADER + ";saldo;valor_contrato").split(";");
            for (int i = 0; i < columns.length; i++) {
                header.createCell(i).setCellValue(columns[i]);
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("52998224725");
            row.createCell(1).setCellValue("Maria");
            row.createCell(2).setCellValue(1234567890123456789d);
            row.createCell(3).setCellValue("10/03/2026");
            // número formatado como moeda brasileira: lido como valor exato, sem separador de milhar
            row.createCell(4).setCellValue(1000d);
            org.apache.poi.ss.usermodel.CellStyle money = workbook.createCellStyle();
            money.setDataFormat(workbook.createDataFormat().getFormat("\"R$\" #,##0.00"));
            row.getCell(4).setCellStyle(money);
            // "número armazenado como texto"
            row.createCell(5).setCellValue("1.000");
            row.createCell(6).setCellFormula("1234.5*2");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
            workbook.write(out);
        }
        Collector result = read(out.toByteArray(), FileLayout.standard(ImportFormat.XLSX));
        assertThat(result.rows).singleElement().satisfies(r -> {
            assertThat(r.get(CanonicalField.PRINCIPAL_AMOUNT)).contains("1000");
            assertThat(r.isTyped(CanonicalField.PRINCIPAL_AMOUNT)).isTrue();
            assertThat(r.get(CanonicalField.REPORTED_BALANCE)).contains("1.000");
            assertThat(r.isTyped(CanonicalField.REPORTED_BALANCE)).isFalse();
            assertThat(r.get(CanonicalField.CONTRACT_AMOUNT)).contains("2469");
            assertThat(r.isTyped(CanonicalField.CONTRACT_AMOUNT)).isTrue();
            // identificador numérico longo: 15 dígitos significativos (o Excel perdeu os demais)
            assertThat(r.get(CanonicalField.TITLE_EXTERNAL_ID)).contains("1234567890123460000");
            assertThat(r.isTyped(CanonicalField.TITLE_EXTERNAL_ID)).isTrue();
        });
    }

    @Test
    void apiNumbersAreTypedAndStringsAreText() {
        String json = """
                [{"documento":"52998224725","nome":"Maria","id_titulo":"T-1","vencimento":"2026-03-10",
                  "valor_principal":1000.50,"saldo":"1.000","valor_contrato":12345678901234.56,"parcela":3}]
                """;
        Collector result = read(json.getBytes(StandardCharsets.UTF_8), FileLayout.standard(ImportFormat.JSON));
        assertThat(result.rows).singleElement().satisfies(r -> {
            assertThat(r.get(CanonicalField.PRINCIPAL_AMOUNT)).contains("1000.5");
            assertThat(r.isTyped(CanonicalField.PRINCIPAL_AMOUNT)).isTrue();
            assertThat(r.get(CanonicalField.REPORTED_BALANCE)).contains("1.000");
            assertThat(r.isTyped(CanonicalField.REPORTED_BALANCE)).isFalse();
            assertThat(r.get(CanonicalField.CONTRACT_AMOUNT)).contains("12345678901234.56");
            assertThat(r.get(CanonicalField.INSTALLMENT_NUMBER)).contains("3");
        });
    }

    private static org.apache.poi.ss.usermodel.CellStyle dateStyle(XSSFWorkbook workbook) {
        org.apache.poi.ss.usermodel.CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy"));
        return style;
    }

    @Test
    void rejectsZipBombsBeforeInflating() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i < 20; i++) {
                zip.write(zeros);
            }
            zip.closeEntry();
        }
        assertThatThrownBy(() -> read(out.toByteArray(), FileLayout.standard(ImportFormat.XLSX)))
                .isInstanceOf(ImportFileException.class).hasFieldOrPropertyWithValue("code", "import.file_rejected");
    }

    @Test
    void rejectsContentThatIsNotASpreadsheet() {
        assertThatThrownBy(() -> read("not a zip".getBytes(StandardCharsets.UTF_8), FileLayout.standard(ImportFormat.XLSX)))
                .isInstanceOf(ImportFileException.class).hasFieldOrPropertyWithValue("code", "import.file_unreadable");
    }

    @Test
    void readsApiRecordsAndReportsUnknownKeys() {
        String json = """
                [{"documento":"52998224725","nome":"Maria","id_titulo":"T-1","vencimento":"2026-03-10",
                  "valor_principal":"10","coobrigado_3_documento":"x"},
                 {"documento":"11144477735","nome":"João","id_titulo":"T-2","vencimento":"2026-03-10","valor_principal":"20"}]
                """;
        Collector result = read(json.getBytes(StandardCharsets.UTF_8), FileLayout.standard(ImportFormat.JSON));
        assertThat(result.rows).extracting(SourceRow::rowNumber).containsExactly(1L, 2L);
        assertThat(result.ignored).containsExactly("coobrigado_3_documento");
        assertThatThrownBy(() -> read("[{\"documento\":".getBytes(StandardCharsets.UTF_8),
                FileLayout.standard(ImportFormat.JSON))).isInstanceOf(ImportFileException.class);
    }

    @Test
    void sinkExceptionsPropagateUnchanged() {
        String csv = HEADER + "\n52998224725;Maria;T-1;10/03/2026;10\n";
        IllegalStateException stop = new IllegalStateException("parar");
        RecordSink failing = row -> {
            throw stop;
        };
        assertThatThrownBy(() -> reader.read(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                FileLayout.standard(ImportFormat.CSV), failing)).isSameAs(stop);
    }
}
