package br.com.gestorcobranca.analytics.application.service;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsExportFormatTest {

    final List<List<Object>> rows = List.of(
            Arrays.asList("totais", "ALL", "=1+1", "recuperado_liquido", new BigDecimal("1100.00"), null, null, null),
            Arrays.asList("totais", "ALL", "Total", "taxa_recuperacao", new BigDecimal("0.2750"),
                    new BigDecimal("1100.00"), new BigDecimal("4000.00"), null),
            Arrays.asList("custo", "ALL", "Total", "comissao", null, null, null, "privacy_minimum"));

    @Test
    void csvHasBomSemicolonsPlainNumbersAndNeutralizedText() {
        byte[] bytes = AnalyticsExportService.csv(rows);
        assertThat(Arrays.copyOf(bytes, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        List<String> lines = text.lines().toList();
        assertThat(lines.getFirst()).isEqualTo("secao;chave;rotulo;indicador;valor;numerador;denominador;observacao");
        assertThat(lines.get(1)).isEqualTo("totais;ALL;'=1+1;recuperado_liquido;1100.00;;;");
        assertThat(lines.get(2)).isEqualTo("totais;ALL;Total;taxa_recuperacao;0.2750;1100.00;4000.00;");
        assertThat(lines.get(3)).isEqualTo("custo;ALL;Total;comissao;;;;privacy_minimum");
    }

    @Test
    void xlsxWritesNumbersAsNumericCellsAndTextNeverAsFormula() throws Exception {
        byte[] bytes = AnalyticsExportService.xlsx("recovery", rows);
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = book.getSheet("recovery");
            assertThat(sheet.getRow(1).getCell(2).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("'=1+1");
            assertThat(sheet.getRow(1).getCell(4).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(1).getCell(4).getNumericCellValue()).isEqualTo(1100.0);
            assertThat(sheet.getRow(3).getCell(4)).isNull();
        }
    }
}
