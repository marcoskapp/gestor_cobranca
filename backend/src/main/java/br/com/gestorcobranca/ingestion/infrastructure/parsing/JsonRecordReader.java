package br.com.gestorcobranca.ingestion.infrastructure.parsing;

import br.com.gestorcobranca.ingestion.domain.model.FileLayout;
import br.com.gestorcobranca.ingestion.domain.model.ImportFileException;
import br.com.gestorcobranca.ingestion.domain.model.SourceRow;
import br.com.gestorcobranca.ingestion.domain.service.ColumnMapping;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.MappingIterator;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Registros recebidos pela API, guardados como array JSON de objetos com as chaves canônicas.
 * Lidos um objeto por vez; o número da linha é a posição do registro (1..n).
 */
final class JsonRecordReader {

    private static final TypeReference<Map<String, String>> RECORD = new TypeReference<>() {
    };

    private final JsonMapper mapper;

    JsonRecordReader(JsonMapper mapper) {
        this.mapper = mapper;
    }

    void read(InputStream content, FileLayout layout, Consumer<SourceRow> consumer) {
        long rowNumber = 0;
        try (MappingIterator<Map<String, String>> records = mapper.readerFor(RECORD).readValues(content)) {
            while (records.hasNextValue()) {
                Map<String, String> record = records.nextValue();
                rowNumber++;
                SourceRow row = ColumnMapping.fromRecord(layout, rowNumber, record);
                if (!row.isBlank()) {
                    try {
                        consumer.accept(row);
                    } catch (RuntimeException e) {
                        throw new ConsumerFailure(e);
                    }
                }
            }
        } catch (ConsumerFailure e) {
            throw e.original();
        } catch (JacksonIOException e) {
            throw new UncheckedIOException("Falha de leitura dos registros de importação", e.getCause());
        } catch (JacksonException e) {
            throw new ImportFileException("import.json_malformed",
                    "Registros em JSON malformado após o registro %d".formatted(rowNumber));
        }
    }
}
