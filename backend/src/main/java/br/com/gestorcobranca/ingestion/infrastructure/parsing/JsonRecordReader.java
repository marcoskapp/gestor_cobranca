package br.com.gestorcobranca.ingestion.infrastructure.parsing;

import br.com.gestorcobranca.ingestion.application.port.out.ImportRecordReader.RecordSink;
import br.com.gestorcobranca.ingestion.domain.model.FileLayout;
import br.com.gestorcobranca.ingestion.domain.model.ImportFileException;
import br.com.gestorcobranca.ingestion.domain.model.SourceRow;
import br.com.gestorcobranca.ingestion.domain.service.ColumnMapping;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MappingIterator;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Registros recebidos pela API, guardados como array JSON de objetos com as chaves canônicas.
 * Lidos um objeto por vez; o número da linha é a posição do registro (1..n). Números JSON são valores <b>tipados</b>
 * (lidos como decimal exato, sem passar por {@code double}); strings seguem como texto e passam pela análise do
 * formato por coluna, como no CSV.
 */
final class JsonRecordReader {

    private static final TypeReference<Map<String, JsonNode>> RECORD = new TypeReference<>() {
    };

    private final JsonMapper mapper;

    JsonRecordReader(JsonMapper mapper) {
        this.mapper = mapper;
    }

    void read(InputStream content, FileLayout layout, RecordSink sink) {
        long rowNumber = 0;
        Set<String> unknownKeys = new LinkedHashSet<>();
        try (MappingIterator<Map<String, JsonNode>> records = mapper.readerFor(RECORD)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValues(content)) {
            while (records.hasNextValue()) {
                Map<String, JsonNode> record = records.nextValue();
                rowNumber++;
                Map<String, String> values = new LinkedHashMap<>();
                Set<String> typed = new HashSet<>();
                record.forEach((key, node) -> {
                    if (node == null || node.isNull()) {
                        return;
                    }
                    if (node.isNumber()) {
                        values.put(key, node.decimalValue().stripTrailingZeros().toPlainString());
                        typed.add(key);
                    } else if (node.isValueNode()) {
                        values.put(key, node.asString());
                    }
                });
                SourceRow row = ColumnMapping.fromRecord(layout, rowNumber, values, typed, unknownKeys);
                if (!row.isBlank()) {
                    try {
                        sink.row(row);
                    } catch (RuntimeException e) {
                        throw new ConsumerFailure(e);
                    }
                }
            }
            sink.ignoredColumns(List.copyOf(unknownKeys));
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
