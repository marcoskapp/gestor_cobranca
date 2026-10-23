package br.com.gestorcobranca.ingestion.domain.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Linha lida da origem, já mapeada para campos canônicos. Valores vêm sem espaços nas pontas;
 * campos vazios não aparecem.
 *
 * @param rowNumber linha no arquivo (cabeçalho = 1, quando houver) ou posição do registro na API (1..n)
 */
public record SourceRow(long rowNumber, Map<CanonicalField, String> values) {

    public SourceRow {
        EnumMap<CanonicalField, String> cleaned = new EnumMap<>(CanonicalField.class);
        values.forEach((field, value) -> {
            if (value != null && !value.isBlank()) {
                cleaned.put(field, value.strip());
            }
        });
        values = Collections.unmodifiableMap(cleaned);
    }

    public Optional<String> get(CanonicalField field) {
        return Optional.ofNullable(values.get(field));
    }

    public boolean isBlank() {
        return values.isEmpty();
    }
}
