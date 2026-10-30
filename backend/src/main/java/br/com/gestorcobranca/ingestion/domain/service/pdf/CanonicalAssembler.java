package br.com.gestorcobranca.ingestion.domain.service.pdf;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.format.AmountReading;
import br.com.gestorcobranca.ingestion.domain.model.format.FormatOption;
import br.com.gestorcobranca.ingestion.domain.model.format.MonetaryFields;
import br.com.gestorcobranca.ingestion.domain.model.pdf.CanonicalRecord;
import br.com.gestorcobranca.ingestion.domain.model.pdf.CellValue;
import br.com.gestorcobranca.ingestion.domain.model.pdf.DebtorBlock;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractedRecord;
import br.com.gestorcobranca.ingestion.domain.model.pdf.Extraction;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.NumberStyle;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.TableColumn;
import br.com.gestorcobranca.ingestion.domain.model.pdf.RecordOrigin;
import br.com.gestorcobranca.ingestion.domain.model.pdf.SourceRef;
import br.com.gestorcobranca.ingestion.domain.service.format.AmountParser;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Converte o que o intérprete encontrou em títulos do layout canônico, aplicando os destinos do mapa:
 * <ul>
 *   <li>coluna da tabela → campo canônico; várias colunas para o mesmo campo são unidas com {@value #JOIN} na ordem do
 *       cabeçalho (ex.: empresa + número + parcela = {@code id_titulo});</li>
 *   <li>campos do bloco do devedor e do documento completam cada título (o da tabela prevalece);</li>
 *   <li>valores monetários são lidos com o separador do mapa pelo {@link AmountParser} do ingestion e vão em texto
 *       canônico; valor que não pôde ser lido segue como está e é recusado pelo validador do ingestion;</li>
 *   <li>{@value ExtractionMap#PAID_WHEN_FILLED} preenchido → {@code situacao = PAGO} (se a situação não vier mapeada);</li>
 *   <li>{@value ExtractionMap#INFO_PREFIX}{@code *} → informação adicional (fica na importação, não vai ao lote).</li>
 * </ul>
 */
public final class CanonicalAssembler {

    public static final String JOIN = "-";

    public List<CanonicalRecord> assemble(Extraction extraction, ExtractionMap map) {
        List<TableColumn> ordered = orderedColumns(map);
        List<CanonicalRecord> out = new ArrayList<>();
        for (ExtractedRecord record : extraction.records()) {
            Builder b = new Builder(map.numberFormat());
            boolean paid = false;
            Map<String, List<CellValue>> joined = new LinkedHashMap<>();
            for (TableColumn column : ordered) {
                CellValue cell = record.cells().get(column.header());
                if (cell == null || cell.isBlank()) {
                    continue;
                }
                for (String target : column.targets()) {
                    if (ExtractionMap.PAID_WHEN_FILLED.equals(target)) {
                        paid = true;
                    } else {
                        joined.computeIfAbsent(target, k -> new ArrayList<>()).add(cell);
                    }
                }
            }
            joined.forEach(b::put);
            DebtorBlock block = record.debtorIndex() < extraction.debtors().size()
                    ? extraction.debtors().get(record.debtorIndex()) : null;
            if (block != null) {
                block.fields().forEach((target, cell) -> b.putIfAbsent(target, List.of(cell)));
            }
            extraction.documentFields().forEach((target, cell) -> b.putIfAbsent(target, List.of(cell)));
            if (paid && !b.values.containsKey(CanonicalField.TITLE_SITUATION)) {
                b.values.put(CanonicalField.TITLE_SITUATION, "PAGO");
            }
            out.add(new CanonicalRecord(record.ordinal(), record.debtorIndex(), RecordOrigin.TEXT, null, b.values,
                    b.typed, b.info, b.sources, b.raw, record.lines(), record.lines().isEmpty() ? null
                    : record.lines().getFirst().page()));
        }
        return out;
    }

    /** Colunas na ordem de leitura: linha do registro e posição no cabeçalho. */
    static List<TableColumn> orderedColumns(ExtractionMap map) {
        if (map.table() == null) {
            return List.of();
        }
        List<List<String>> header = map.table().headerLines();
        return map.table().columns().stream().sorted(Comparator.comparingInt(TableColumn::line)
                .thenComparingInt(c -> c.line() <= header.size() ? indexOf(header.get(c.line() - 1), c.header()) : 0))
                .toList();
    }

    private static int indexOf(List<String> labels, String header) {
        int i = labels.indexOf(header);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** Valor monetário em texto canônico com duas casas, ou vazio se não puder ser lido. */
    public static Optional<String> canonicalAmount(String raw, NumberStyle style) {
        AmountReading reading = AmountParser.read(raw, style == NumberStyle.DOT ? FormatOption.DOT : FormatOption.COMMA);
        return reading.ok() ? Optional.of(reading.value().setScale(2, RoundingMode.UNNECESSARY).toPlainString())
                : Optional.empty();
    }

    private static final class Builder {

        final NumberStyle style;
        final Map<CanonicalField, String> values = new EnumMap<>(CanonicalField.class);
        final Set<CanonicalField> typed = EnumSet.noneOf(CanonicalField.class);
        final Map<String, String> info = new LinkedHashMap<>();
        final Map<String, List<SourceRef>> sources = new LinkedHashMap<>();
        final Map<String, String> raw = new LinkedHashMap<>();

        Builder(NumberStyle style) {
            this.style = style;
        }

        void putIfAbsent(String target, List<CellValue> cells) {
            String key = target.startsWith(ExtractionMap.INFO_PREFIX) ? target
                    : CanonicalField.byColumn(target).map(CanonicalField::column).orElse(null);
            if (key != null && !sources.containsKey(key)) {
                put(target, cells);
            }
        }

        void put(String target, List<CellValue> cells) {
            String text = String.join(JOIN, cells.stream().map(CellValue::text).toList());
            List<SourceRef> refs = cells.stream().map(CellValue::source).toList();
            if (target.startsWith(ExtractionMap.INFO_PREFIX)) {
                info.put(target.substring(ExtractionMap.INFO_PREFIX.length()), text);
                sources.put(target, refs);
                raw.put(target, text);
                return;
            }
            Optional<CanonicalField> field = CanonicalField.byColumn(target);
            if (field.isEmpty()) {
                return;
            }
            sources.put(field.get().column(), refs);
            raw.put(field.get().column(), text);
            if (MonetaryFields.ALL.contains(field.get()) && cells.size() == 1) {
                Optional<String> amount = canonicalAmount(text, style);
                if (amount.isPresent()) {
                    values.put(field.get(), amount.get());
                    typed.add(field.get());
                    return;
                }
            }
            values.put(field.get(), text);
        }
    }
}
