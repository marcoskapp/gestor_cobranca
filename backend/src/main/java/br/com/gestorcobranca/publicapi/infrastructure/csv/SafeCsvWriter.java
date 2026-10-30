package br.com.gestorcobranca.publicapi.infrastructure.csv;

import br.com.gestorcobranca.publicapi.application.port.out.CsvWriter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CSV para planilhas (separador ";", UTF-8 com BOM). Células de texto iniciadas por {@code = + - @}, tab ou retorno de
 * carro recebem apóstrofo (neutralização de fórmulas — CSV injection); números são escritos como números.
 */
@Component
class SafeCsvWriter implements CsvWriter {

    @Override
    public Sheet open(OutputStream output, List<String> header) {
        try {
            Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
            writer.write('﻿');
            CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setDelimiter(';')
                    .setRecordSeparator("\r\n").get());
            printer.printRecord(header);
            return new Sheet() {
                @Override
                public void row(List<?> values) {
                    try {
                        printer.printRecord(values.stream().map(SafeCsvWriter::safe).toList());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }

                @Override
                public void close() {
                    try {
                        printer.flush();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Object safe(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number) {
            return value;
        }
        String s = value.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            return "'" + s;
        }
        return s;
    }
}
