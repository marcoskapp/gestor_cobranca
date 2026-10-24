package br.com.gestorcobranca.ingestion.domain.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Linha lida da origem, já mapeada para campos canônicos. Valores vêm sem espaços nas pontas;
 * campos vazios não aparecem.
 *
 * @param rowNumber linha no arquivo (cabeçalho = 1, quando houver) ou posição do registro na API (1..n)
 * @param typed     campos cujo valor veio <b>tipado</b> como número (célula numérica do XLSX ou número JSON), em
 *                  texto canônico ({@code -1234.56}, sem milhar): não há ambiguidade de separador
 */
public record SourceRow(long rowNumber, Map<CanonicalField, String> values, Set<CanonicalField> typed) {

    public SourceRow {
        EnumMap<CanonicalField, String> cleaned = new EnumMap<>(CanonicalField.class);
        values.forEach((field, value) -> {
            if (value != null && !value.isBlank()) {
                cleaned.put(field, value.strip());
            }
        });
        values = Collections.unmodifiableMap(cleaned);
        EnumSet<CanonicalField> numeric = EnumSet.noneOf(CanonicalField.class);
        if (typed != null) {
            typed.stream().filter(cleaned::containsKey).forEach(numeric::add);
        }
        typed = Collections.unmodifiableSet(numeric);
    }

    /** Linha só com valores em texto. */
    public SourceRow(long rowNumber, Map<CanonicalField, String> values) {
        this(rowNumber, values, Set.of());
    }

    public Optional<String> get(CanonicalField field) {
        return Optional.ofNullable(values.get(field));
    }

    /** O valor do campo veio tipado como número. */
    public boolean isTyped(CanonicalField field) {
        return typed.contains(field);
    }

    public boolean isBlank() {
        return values.isEmpty();
    }
}
