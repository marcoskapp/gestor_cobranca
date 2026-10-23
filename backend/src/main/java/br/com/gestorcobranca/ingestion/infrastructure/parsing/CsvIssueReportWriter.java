package br.com.gestorcobranca.ingestion.infrastructure.parsing;

import br.com.gestorcobranca.ingestion.application.port.out.IssueLine;
import br.com.gestorcobranca.ingestion.application.port.out.IssueReportWriter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Relatório de ocorrências em CSV (UTF-8 com BOM, separado por ponto e vírgula, para abrir
 * direto no Excel em português). Células que começam com {@code = + - @} recebem apóstrofo
 * inicial, neutralizando injeção de fórmulas a partir de conteúdo enviado pelo credor.
 */
@Component
class CsvIssueReportWriter implements IssueReportWriter {

    private static final String[] HEADER = {"linha", "severidade", "campo", "codigo", "mensagem", "valor"};

    @Override
    public Report open(OutputStream output) {
        try {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
            writer.write('﻿');
            CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setDelimiter(';').get());
            printer.printRecord((Object[]) HEADER);
            return new CsvReport(printer);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao escrever o relatório de ocorrências", e);
        }
    }

    private record CsvReport(CSVPrinter printer) implements Report {

        @Override
        public void write(IssueLine line) {
            try {
                printer.printRecord(line.rowNumber(), line.severity(), safe(line.field()), safe(line.code()),
                        safe(line.message()), safe(line.valueExcerpt()));
            } catch (IOException e) {
                throw new UncheckedIOException("Falha ao escrever o relatório de ocorrências", e);
            }
        }

        @Override
        public void close() {
            try {
                printer.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("Falha ao escrever o relatório de ocorrências", e);
            }
        }

        private static String safe(String value) {
            if (value == null || value.isEmpty()) {
                return value;
            }
            char first = value.charAt(0);
            return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                    ? "'" + value : value;
        }
    }
}
