package br.com.gestorcobranca.ingestion.domain.service.pdf;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.format.MonetaryFields;
import br.com.gestorcobranca.ingestion.domain.model.pdf.CanonicalRecord;
import br.com.gestorcobranca.ingestion.domain.model.pdf.CellValue;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ControlValue;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.ControlTotal;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.DebtorSection;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.DebtorSource;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.TableColumn;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.TableSection;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.TotalScope;
import br.com.gestorcobranca.ingestion.domain.model.pdf.RecordOrigin;
import br.com.gestorcobranca.ingestion.domain.model.pdf.VisualReading;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Leitura visual → títulos canônicos, totais de controle e um mapa descritivo (só para a tela; não é reaplicável). Os
 * valores monetários passam pela mesma leitura do {@link CanonicalAssembler}. O devedor de cada título é agrupado pelo
 * documento (ou nome) transcrito.
 */
public final class VisualAssembler {

    public List<CanonicalRecord> records(VisualReading reading) {
        List<CanonicalRecord> out = new ArrayList<>();
        Map<String, Integer> debtors = new LinkedHashMap<>();
        int ordinal = 1;
        for (VisualReading.Item item : reading.records()) {
            Map<CanonicalField, String> values = new EnumMap<>(CanonicalField.class);
            Set<CanonicalField> typed = EnumSet.noneOf(CanonicalField.class);
            Map<String, String> info = new LinkedHashMap<>();
            Map<String, String> raw = new LinkedHashMap<>();
            item.values().forEach((target, text) -> {
                if (text == null || text.isBlank() || target == null) {
                    return;
                }
                String value = text.strip();
                if (target.startsWith(ExtractionMap.INFO_PREFIX)) {
                    info.put(target.substring(ExtractionMap.INFO_PREFIX.length()), value);
                    raw.put(target, value);
                    return;
                }
                Optional<CanonicalField> field = CanonicalField.byColumn(target);
                if (field.isEmpty()) {
                    return;
                }
                raw.put(field.get().column(), value);
                if (MonetaryFields.ALL.contains(field.get())) {
                    Optional<String> amount = CanonicalAssembler.canonicalAmount(value, reading.numberFormat());
                    if (amount.isPresent()) {
                        values.put(field.get(), amount.get());
                        typed.add(field.get());
                        return;
                    }
                }
                values.put(field.get(), value);
            });
            String key = Optional.ofNullable(values.get(CanonicalField.DEBTOR_DOCUMENT)).map(d -> d.replaceAll("\\D", ""))
                    .filter(d -> !d.isEmpty()).orElseGet(() -> Optional.ofNullable(values.get(CanonicalField.DEBTOR_NAME))
                            .map(TextMatch::normalize).orElse(""));
            int debtor = debtors.computeIfAbsent(key, k -> debtors.size());
            out.add(new CanonicalRecord(ordinal++, debtor, RecordOrigin.VISUAL, clamp(item.confidence()), values, typed,
                    info, Map.of(), raw, List.of(), item.page()));
        }
        return out;
    }

    /**
     * Totais lidos visualmente. Sem posições não há como atribuir um total a um bloco de devedor: totais de bloco só
     * são conferidos quando o documento tem um único devedor; os demais ficam como totais do documento.
     */
    public List<ControlValue> controls(VisualReading reading) {
        long debtors = records(reading).stream().map(CanonicalRecord::debtorIndex).distinct().count();
        List<ControlValue> out = new ArrayList<>();
        for (VisualReading.Control c : reading.controls()) {
            if (c.value() == null || c.value().isBlank() || c.label() == null || c.target() == null) {
                continue;
            }
            if (c.scope() == TotalScope.BLOCK && debtors > 1) {
                continue;
            }
            out.add(new ControlValue(-1, new ControlTotal(c.label(), null, c.target(), TotalScope.DOCUMENT),
                    new CellValue(c.value().strip(), null)));
        }
        return out;
    }

    /** Mapa descritivo do layout lido visualmente (exibido na tela; a edição não se aplica). */
    public ExtractionMap describedMap(VisualReading reading) {
        List<TableColumn> columns = new ArrayList<>();
        List<String> header = new ArrayList<>();
        for (VisualReading.Column c : reading.columns()) {
            if (c.header() == null || c.header().isBlank() || header.contains(c.header())) {
                continue;
            }
            header.add(c.header());
            columns.add(new TableColumn(c.header(), 1, c.target() == null || c.target().isBlank() ? List.of()
                    : List.of(c.target()), null, null));
        }
        List<ControlTotal> controls = controls(reading).stream().map(ControlValue::definition).distinct().toList();
        return new ExtractionMap(ExtractionMap.SCHEMA_VERSION, reading.documentType(), reading.numberFormat(),
                new DebtorSection(DebtorSource.COLUMNS, null, List.of()), List.of(),
                new TableSection(1, header.isEmpty() ? List.of() : List.of(header), null, List.of(), columns), controls,
                List.of());
    }

    private static Double clamp(double confidence) {
        return Math.max(0, Math.min(1, confidence));
    }
}
