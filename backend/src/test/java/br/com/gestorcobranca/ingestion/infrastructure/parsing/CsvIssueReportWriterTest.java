package br.com.gestorcobranca.ingestion.infrastructure.parsing;

import br.com.gestorcobranca.ingestion.application.port.out.IssueLine;
import br.com.gestorcobranca.ingestion.application.port.out.IssueReportWriter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CsvIssueReportWriterTest {

    @Test
    void writesExcelFriendlyCsvAndNeutralizesFormulas() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (IssueReportWriter.Report report = new CsvIssueReportWriter().open(out)) {
            report.write(new IssueLine(2, "ERROR", "produto", "row.formula_not_allowed", "Conteúdo com fórmula", "=1+1"));
            report.write(new IssueLine(3, "WARNING", "saldo", "row.balance_invalid", "Saldo; inválido", "@SUM(A1)"));
            report.write(new IssueLine(4, "ERROR", "valor_principal", "row.principal_negative", "x", "-10"));
        }
        String csv = out.toString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿linha;severidade;campo;codigo;mensagem;valor");
        assertThat(csv).contains("2;ERROR;produto;row.formula_not_allowed;Conteúdo com fórmula;'=1+1");
        assertThat(csv).contains("\"Saldo; inválido\";'@SUM(A1)");
        assertThat(csv).contains(";'-10");
        assertThat(csv).doesNotContain(";=1+1");
    }
}
