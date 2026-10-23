package br.com.gestorcobranca.ingestion.infrastructure.parsing;

import br.com.gestorcobranca.ingestion.domain.model.FileLayout;
import br.com.gestorcobranca.ingestion.domain.model.ImportFileException;
import br.com.gestorcobranca.ingestion.domain.model.SourceRow;
import br.com.gestorcobranca.ingestion.domain.service.ColumnMapping;
import org.apache.commons.csv.CSVException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.function.Consumer;

/**
 * CSV em streaming (Commons CSV): um registro por vez. O número da linha é o número do registro
 * (linhas vazias contam, para coincidir com a linha do editor quando não há quebras de linha
 * dentro de campos entre aspas). Caracteres inválidos na codificação do layout são recusados —
 * nunca substituídos silenciosamente.
 */
final class CsvRecordReader {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    void read(InputStream content, FileLayout layout, Consumer<SourceRow> consumer) {
        Charset charset = Charset.forName(layout.encoding());
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(layout.delimiter())
                .setIgnoreEmptyLines(false)
                .get();
        try (CSVParser parser = format.parse(new BufferedReader(new InputStreamReader(withoutBom(content, charset),
                charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT))))) {
            ColumnMapping mapping = layout.hasHeader() ? null : ColumnMapping.resolve(layout, null);
            Iterator<CSVRecord> records = parser.iterator();
            while (true) {
                CSVRecord record;
                try {
                    if (!records.hasNext()) {
                        break;
                    }
                    record = records.next();
                } catch (UncheckedIOException e) {
                    throw translate(e.getCause(), layout, parser.getCurrentLineNumber());
                }
                if (mapping == null) {
                    if (record.stream().allMatch(String::isBlank)) {
                        continue;
                    }
                    mapping = ColumnMapping.resolve(layout, record.toList());
                    continue;
                }
                SourceRow row = mapping.map(record.getRecordNumber(), record.toList());
                if (!row.isBlank()) {
                    consumer.accept(row);
                }
            }
            if (mapping == null) {
                throw new ImportFileException("import.header_missing", "Arquivo sem linha de cabeçalho");
            }
        } catch (IOException e) {
            throw translate(e, layout, 0);
        }
    }

    private static RuntimeException translate(IOException e, FileLayout layout, long line) {
        if (e instanceof CharacterCodingException) {
            return new ImportFileException("import.encoding_invalid",
                    "Arquivo contém caracteres inválidos para a codificação %s".formatted(layout.encoding()));
        }
        if (e instanceof CSVException) {
            return new ImportFileException("import.csv_malformed",
                    "CSV malformado próximo à linha %d (aspas não fechadas ou caractere inválido)".formatted(line));
        }
        return new UncheckedIOException("Falha de leitura do arquivo de importação", e);
    }

    private static InputStream withoutBom(InputStream content, Charset charset) throws IOException {
        if (!charset.equals(StandardCharsets.UTF_8)) {
            return content;
        }
        PushbackInputStream in = new PushbackInputStream(content, UTF8_BOM.length);
        byte[] head = in.readNBytes(UTF8_BOM.length);
        boolean bom = head.length == UTF8_BOM.length && head[0] == UTF8_BOM[0] && head[1] == UTF8_BOM[1]
                && head[2] == UTF8_BOM[2];
        if (!bom) {
            in.unread(head);
        }
        return in;
    }
}
