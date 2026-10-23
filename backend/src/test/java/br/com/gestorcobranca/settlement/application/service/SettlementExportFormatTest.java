package br.com.gestorcobranca.settlement.application.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementExportFormatTest {

    @Test
    void csvHasFixedHeaderSemicolonsAndNeutralizedFormulas() {
        List<String> row = new ArrayList<>(Arrays.asList(new String[SettlementExportService.HEADER.length]));
        for (int i = 0; i < row.size(); i++) {
            row.set(i, "");
        }
        row.set(0, "AJUSTE");
        row.set(18, "-15.00");
        row.set(29, "=HYPERLINK(\"x\")");
        String csv = new String(SettlementExportService.csv(List.of(row)), StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).startsWith("tipo_registro;demonstrativo_id;credor_id;periodo_inicio;periodo_fim;chave;");
        assertThat(lines[0].split(";")).hasSize(30);
        // número negativo preservado; texto que começa com = vira texto
        assertThat(lines[1]).contains(";-15.00;").contains("'=HYPERLINK");
    }

    @Test
    void neutralizationKeepsNumbersAndPlainText() {
        assertThat(SettlementExportService.neutralizeFormula("-855.00")).isEqualTo("-855.00");
        assertThat(SettlementExportService.neutralizeFormula("+5")).isEqualTo("'+5");
        assertThat(SettlementExportService.neutralizeFormula("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(SettlementExportService.neutralizeFormula("T-60/1")).isEqualTo("T-60/1");
    }
}
