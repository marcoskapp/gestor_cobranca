package br.com.gestorcobranca.ingestion.infrastructure.parsing;

import br.com.gestorcobranca.ingestion.application.port.out.CanonicalFileWriter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.QuoteMode;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * CSV do layout canônico gerado a partir do PDF aprovado: UTF-8 sem BOM, separador {@code ;}, aspas em todos os campos
 * (nomes e endereços com {@code ;} ou aspas chegam intactos ao leitor do ingestion). Os valores seguem como lidos no
 * PDF — fórmulas não são neutralizadas aqui porque o validador do ingestion as recusa nos campos de texto.
 */
@Component
class CsvCanonicalFileWriter implements CanonicalFileWriter {

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder().setDelimiter(';').setRecordSeparator("\n")
            .setQuoteMode(QuoteMode.ALL).get();

    @Override
    public byte[] csv(List<String> columns, List<Map<String, String>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(out, StandardCharsets.UTF_8), FORMAT)) {
            printer.printRecord(columns);
            for (Map<String, String> row : rows) {
                for (String column : columns) {
                    printer.print(row.getOrDefault(column, ""));
                }
                printer.println();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
