package br.com.gestorcobranca.ingestion.domain.service;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.FileLayout;
import br.com.gestorcobranca.ingestion.domain.model.ImportFileException;
import br.com.gestorcobranca.ingestion.domain.model.SourceRow;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Associação entre as colunas de um arquivo tabular (CSV/XLSX) e os campos canônicos,
 * resolvida uma vez a partir do cabeçalho (ou das posições do layout) e aplicada a cada linha.
 * Colunas do arquivo que o layout não usa são ignoradas e listadas em {@link #ignoredColumns()},
 * para o aviso do lote ({@link IgnoredColumns}).
 */
public final class ColumnMapping {

    private final Map<CanonicalField, Integer> indexes;
    private final List<String> ignoredColumns;

    private ColumnMapping(Map<CanonicalField, Integer> indexes, List<String> ignoredColumns) {
        this.indexes = indexes;
        this.ignoredColumns = List.copyOf(ignoredColumns);
    }

    /** Cabeçalhos do arquivo que não correspondem a nenhuma coluna do layout (vazio sem cabeçalho). */
    public List<String> ignoredColumns() {
        return ignoredColumns;
    }

    /**
     * @param header células do cabeçalho; ignorado (pode ser nulo) em layout sem cabeçalho
     * @throws ImportFileException coluna obrigatória ausente ou cabeçalho repetido
     */
    public static ColumnMapping resolve(FileLayout layout, List<String> header) {
        Map<CanonicalField, Integer> indexes = new EnumMap<>(CanonicalField.class);
        if (!layout.hasHeader()) {
            layout.columns().forEach(c -> indexes.put(c.field(), c.position() - 1));
            return new ColumnMapping(indexes, List.of());
        }
        if (header == null || header.stream().allMatch(h -> h == null || h.isBlank())) {
            throw new ImportFileException("import.header_missing", "Arquivo sem linha de cabeçalho");
        }
        Map<String, Integer> positions = new LinkedHashMap<>();
        Map<String, String> original = new HashMap<>();
        List<String> repeated = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String cell = header.get(i);
            if (cell == null || cell.isBlank()) {
                continue;
            }
            String key = CanonicalField.normalizeHeader(stripBom(cell));
            if (positions.putIfAbsent(key, i) != null) {
                repeated.add(cell.strip());
            }
            original.putIfAbsent(key, stripBom(cell).strip());
        }
        List<String> missing = new ArrayList<>();
        for (FileLayout.Column column : layout.columns()) {
            String key = CanonicalField.normalizeHeader(column.header());
            if (repeated.stream().anyMatch(r -> CanonicalField.normalizeHeader(r).equals(key))) {
                throw new ImportFileException("import.column_ambiguous",
                        "Coluna repetida no cabeçalho do arquivo: " + column.header());
            }
            Integer index = positions.get(key);
            if (index != null) {
                indexes.put(column.field(), index);
            } else if (column.field().required()) {
                missing.add(column.header());
            }
        }
        if (!missing.isEmpty()) {
            throw new ImportFileException("import.columns_missing",
                    "Colunas obrigatórias ausentes no cabeçalho: " + String.join(", ", missing));
        }
        Set<String> mapped = layout.columns().stream().map(c -> CanonicalField.normalizeHeader(c.header()))
                .collect(Collectors.toSet());
        List<String> ignored = positions.keySet().stream().filter(k -> !mapped.contains(k)).map(original::get).toList();
        return new ColumnMapping(indexes, ignored);
    }

    /**
     * Linha de registro JSON (API): as chaves são os cabeçalhos do layout. Chaves desconhecidas são
     * acrescentadas a {@code unknownKeys} (até {@link IgnoredColumns#MAX_LISTED} chaves distintas).
     */
    public static SourceRow fromRecord(FileLayout layout, long rowNumber, Map<String, String> record,
                                       Set<String> unknownKeys) {
        Map<String, CanonicalField> byKey = layout.columns().stream()
                .collect(Collectors.toMap(c -> CanonicalField.normalizeHeader(c.header()), FileLayout.Column::field));
        Map<CanonicalField, String> values = new EnumMap<>(CanonicalField.class);
        record.forEach((key, value) -> {
            CanonicalField field = key == null ? null : byKey.get(CanonicalField.normalizeHeader(key));
            if (field != null && value != null) {
                values.put(field, value);
            } else if (field == null && key != null && unknownKeys.size() < IgnoredColumns.MAX_LISTED) {
                unknownKeys.add(key.strip());
            }
        });
        return new SourceRow(rowNumber, values);
    }

    public SourceRow map(long rowNumber, List<String> cells) {
        Map<CanonicalField, String> values = new EnumMap<>(CanonicalField.class);
        indexes.forEach((field, index) -> {
            if (index < cells.size() && cells.get(index) != null) {
                values.put(field, cells.get(index));
            }
        });
        return new SourceRow(rowNumber, values);
    }

    private static String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }
}
