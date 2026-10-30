package br.com.gestorcobranca.ingestion.domain.service.pdf;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.format.MonetaryFields;
import br.com.gestorcobranca.ingestion.domain.model.pdf.CellValue;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ControlValue;
import br.com.gestorcobranca.ingestion.domain.model.pdf.DebtorBlock;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractedRecord;
import br.com.gestorcobranca.ingestion.domain.model.pdf.Extraction;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.ControlTotal;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.DebtorSource;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.LabelField;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.TableColumn;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.TableSection;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.ValuePosition;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.ValueTransform;
import br.com.gestorcobranca.ingestion.domain.model.pdf.LineNote;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfText;
import br.com.gestorcobranca.ingestion.domain.model.pdf.SourceRef;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TextLine;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TextWord;
import br.com.gestorcobranca.ingestion.domain.service.BrazilianFormats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Intérprete <strong>determinístico</strong> do mapa de extração: aplica o {@link ExtractionMap} à camada de texto
 * posicionada e devolve o que encontrou, com a origem de cada valor (página, linha e caixa). Mesmo texto e mesmo
 * mapa produzem sempre o mesmo resultado; nenhum valor é produzido fora do texto do PDF.
 * <p>
 * Leitura, linha a linha e na ordem do documento (todas as páginas em sequência):
 * <ol>
 *   <li>linhas com um texto de {@code ignoreLinesContaining} são descartadas;</li>
 *   <li>a linha que contém, na ordem, todos os rótulos da 1ª linha do cabeçalho abre (ou reabre, em outra página) a
 *       tabela; as linhas seguintes do cabeçalho são conferidas logo abaixo. Cada coluna ocupa a faixa entre os pontos
 *       médios dos rótulos vizinhos (ou os limites explícitos do mapa);</li>
 *   <li>dentro da tabela, uma linha com a coluna-âncora preenchida inicia um título; as linhas seguintes sem âncora
 *       completam o título até {@code linesPerRecord}; o topo de uma nova página antes do cabeçalho repetido nunca é
 *       anexado a um título; o restante vira "linha não reconhecida";</li>
 *   <li>uma linha com um marcador de fim (ou rótulo de total de controle) fecha a tabela e tem os totais lidos;</li>
 *   <li>o rótulo de abertura do bloco do devedor inicia um novo devedor; os campos "Rótulo: valor" são lidos à direita
 *       (até um intervalo grande ou outro rótulo) ou abaixo do rótulo. Blocos consecutivos do mesmo devedor
 *       (cabeçalho repetido a cada página) são unidos.</li>
 * </ol>
 * Palavras próximas (intervalo menor que ~0,8 caractere) formam um trecho, atribuído inteiro à coluna que contém o
 * seu centro (um texto longo da 2ª linha, como a conta contábil, não "invade" a coluna-âncora). A coluna-âncora só
 * inicia um título se o texto for do tipo do seu destino: data para campos de data, valor para campos monetários.
 */
public final class MapInterpreter {

    private static final Pattern CODE = Pattern.compile("\\d[\\d./-]*");
    private static final Set<CanonicalField> DATE_FIELDS = Set.of(CanonicalField.DUE_DATE, CanonicalField.CONTRACT_DATE,
            CanonicalField.BALANCE_REFERENCE_DATE, CanonicalField.BIRTH_DATE);
    private static final double PHRASE_GAP_FACTOR = 0.8;
    private static final double VALUE_GAP_FACTOR = 4.0;
    private static final double MIN_VALUE_GAP = 3.0;

    public Extraction interpret(PdfText text, ExtractionMap map) {
        return new Run(text, map).execute();
    }

    /** Faixa horizontal de uma coluna numa linha do registro. */
    record Zone(String column, double from, double to) {

        boolean contains(double x) {
            return x >= from && x < to;
        }
    }

    // ------------------------------------------------------------------ células

    /** Texto de cada coluna numa linha, pelas faixas informadas. */
    static Map<String, CellValue> cells(TextLine line, List<Zone> zones) {
        Map<String, List<TextWord>> byColumn = new LinkedHashMap<>();
        if (zones == null || zones.isEmpty()) {
            return Map.of();
        }
        for (List<TextWord> phrase : phrases(line.words())) {
            Zone zone = zoneOf(zones, (phrase.getFirst().x0() + phrase.getLast().x1()) / 2);
            if (zone != null) {
                byColumn.computeIfAbsent(zone.column(), k -> new ArrayList<>()).addAll(phrase);
            }
        }
        Map<String, CellValue> out = new LinkedHashMap<>();
        byColumn.forEach((column, words) -> out.put(column, cell(line, words)));
        return out;
    }

    private static Zone zoneOf(List<Zone> zones, double x) {
        for (Zone z : zones) {
            if (z.contains(x)) {
                return z;
            }
        }
        return null;
    }

    static List<List<TextWord>> phrases(List<TextWord> words) {
        List<List<TextWord>> out = new ArrayList<>();
        List<TextWord> current = new ArrayList<>();
        for (TextWord w : words) {
            if (!current.isEmpty()) {
                TextWord prev = current.getLast();
                double gap = w.x0() - prev.x1();
                double limit = Math.max(0.5, PHRASE_GAP_FACTOR * Math.min(prev.charWidth(), w.charWidth()));
                if (gap > limit) {
                    out.add(current);
                    current = new ArrayList<>();
                }
            }
            current.add(w);
        }
        if (!current.isEmpty()) {
            out.add(current);
        }
        return out;
    }

    static CellValue cell(TextLine line, List<TextWord> words) {
        StringBuilder sb = new StringBuilder();
        for (TextWord w : words) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(w.text());
        }
        return new CellValue(sb.toString(), SourceRef.of(line.page(), line.number(), words));
    }

    /**
     * Faixas das colunas a partir das posições dos rótulos encontrados na linha do cabeçalho; nulo se algum rótulo não
     * estiver na linha (na ordem).
     */
    static List<Zone> zonesFromHeader(TextLine line, List<String> labels, List<TableColumn> columns) {
        if (labels.isEmpty()) {
            return null;
        }
        List<double[]> spans = new ArrayList<>();
        int from = 0;
        for (String label : labels) {
            int at = TextMatch.find(line, label, from);
            if (at < 0) {
                return null;
            }
            int size = TextMatch.size(label);
            List<TextWord> words = line.words().subList(at, at + size);
            spans.add(new double[]{words.getFirst().x0(), words.getLast().x1()});
            from = at + size;
        }
        List<Zone> zones = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            double left = i == 0 ? Double.NEGATIVE_INFINITY : (spans.get(i - 1)[1] + spans.get(i)[0]) / 2;
            double right = i == labels.size() - 1 ? Double.POSITIVE_INFINITY : (spans.get(i)[1] + spans.get(i + 1)[0]) / 2;
            zones.add(new Zone(labels.get(i), left, right));
        }
        return applyExplicit(zones, columns);
    }

    /** Limites explícitos do mapa substituem os deduzidos (e as vizinhas são ajustadas para não se sobrepor). */
    private static List<Zone> applyExplicit(List<Zone> zones, List<TableColumn> columns) {
        List<Zone> out = new ArrayList<>(zones);
        for (TableColumn c : columns) {
            if (c.xMin() == null && c.xMax() == null) {
                continue;
            }
            for (int i = 0; i < out.size(); i++) {
                Zone z = out.get(i);
                if (z.column().equals(c.header())) {
                    double from = c.xMin() == null ? z.from() : c.xMin();
                    double to = c.xMax() == null ? z.to() : c.xMax();
                    out.set(i, new Zone(z.column(), from, to));
                    if (i > 0 && out.get(i - 1).to() > from) {
                        Zone prev = out.get(i - 1);
                        out.set(i - 1, new Zone(prev.column(), prev.from(), from));
                    }
                    if (i < out.size() - 1 && out.get(i + 1).from() < to) {
                        Zone next = out.get(i + 1);
                        out.set(i + 1, new Zone(next.column(), to, next.to()));
                    }
                }
            }
        }
        return out;
    }

    /** Linha do registro sem cabeçalho encontrado: limites explícitos ou, com uma coluna só, a linha inteira. */
    static List<Zone> fallbackZones(List<TableColumn> columns) {
        if (columns.isEmpty()) {
            return List.of();
        }
        if (columns.size() == 1 && columns.getFirst().xMin() == null && columns.getFirst().xMax() == null) {
            return List.of(new Zone(columns.getFirst().header(), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY));
        }
        List<Zone> out = new ArrayList<>();
        for (TableColumn c : columns) {
            if (c.xMin() != null && c.xMax() != null) {
                out.add(new Zone(c.header(), c.xMin(), c.xMax()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ "Rótulo: valor"

    /** Valor à direita do rótulo: a 1ª palavra a qualquer distância, as seguintes até um intervalo grande ou outro rótulo. */
    static List<TextWord> valueRight(TextLine line, int start, Set<String> knownLabels) {
        List<TextWord> words = line.words();
        List<TextWord> out = new ArrayList<>();
        for (int i = start; i < words.size(); i++) {
            TextWord w = words.get(i);
            if (startsLabel(line, i, knownLabels) || (w.text().length() > 1 && w.text().endsWith(":"))) {
                break;
            }
            if (!out.isEmpty()) {
                double avg = out.stream().mapToDouble(TextWord::charWidth).average().orElse(1);
                if (w.x0() - out.getLast().x1() > Math.max(MIN_VALUE_GAP, VALUE_GAP_FACTOR * avg)) {
                    break;
                }
            }
            out.add(w);
        }
        return out;
    }

    /** Valor na linha de baixo, começando sob o rótulo. */
    static List<TextWord> valueBelow(TextLine labelLine, int at, int size, TextLine next, Set<String> knownLabels) {
        if (next == null || next.page() != labelLine.page()) {
            return List.of();
        }
        double x0 = labelLine.words().get(at).x0();
        double x1 = labelLine.words().get(at + size - 1).x1();
        double tolerance = labelLine.words().get(at).charWidth() * 2;
        for (int i = 0; i < next.words().size(); i++) {
            TextWord w = next.words().get(i);
            if (w.x1() >= x0 - tolerance && w.x0() <= x1 + tolerance) {
                return valueRight(next, i, knownLabels);
            }
        }
        return List.of();
    }

    private static boolean startsLabel(TextLine line, int index, Set<String> knownLabels) {
        for (String label : knownLabels) {
            if (TextMatch.find(line, label, index) == index) {
                return true;
            }
        }
        return false;
    }

    static List<TextWord> transform(List<TextWord> words, ValueTransform transform) {
        if (words.isEmpty() || transform == ValueTransform.NONE) {
            return words;
        }
        boolean leadingCode = CODE.matcher(words.getFirst().text()).matches();
        return switch (transform) {
            case DROP_LEADING_CODE -> leadingCode && words.size() > 1 ? words.subList(1, words.size()) : words;
            case LEADING_CODE -> leadingCode ? words.subList(0, 1) : List.of();
            case NONE -> words;
        };
    }

    // ------------------------------------------------------------------ execução

    private static final class Run {

        private final ExtractionMap map;
        private final TableSection table;
        private final List<TextLine> lines;
        private final Set<String> knownLabels = new LinkedHashSet<>();
        private final Set<String> endLabels = new LinkedHashSet<>();
        private final List<BlockBuilder> blocks = new ArrayList<>();
        private final List<RecordBuilder> records = new ArrayList<>();
        private final List<PendingControl> controls = new ArrayList<>();
        private final Map<String, CellValue> documentFields = new LinkedHashMap<>();
        private final List<SourceRef> headerRegions = new ArrayList<>();
        private final List<LineNote> notes = new ArrayList<>();
        private final Map<Integer, List<Zone>> zones = new HashMap<>();
        private final Map<Integer, List<TableColumn>> columnsByLine = new HashMap<>();
        private RecordBuilder pending;
        private boolean inTable;
        private boolean pagePreamble;
        private boolean headerFound;
        private int lastPage = -1;

        Run(PdfText text, ExtractionMap map) {
            this.map = map;
            this.table = map.table() == null ? new TableSection(1, List.of(), null, List.of(), List.of()) : map.table();
            this.lines = text.lines().stream().filter(this::kept).toList();
            map.debtor().fields().forEach(f -> knownLabels.add(f.label()));
            map.documentFields().forEach(f -> knownLabels.add(f.label()));
            map.controlTotals().forEach(c -> knownLabels.add(c.label()));
            if (map.debtor().blockStartLabel() != null) {
                knownLabels.add(map.debtor().blockStartLabel());
            }
            knownLabels.removeIf(l -> l == null || l.isBlank());
            endLabels.addAll(table.endMarkers());
            map.controlTotals().forEach(c -> endLabels.add(c.label()));
            endLabels.removeIf(l -> l == null || l.isBlank());
            for (TableColumn c : table.columns()) {
                columnsByLine.computeIfAbsent(c.line(), k -> new ArrayList<>()).add(c);
            }
            if (map.debtor().source() != DebtorSource.HEADER) {
                blocks.add(new BlockBuilder(null));
            }
        }

        private boolean kept(TextLine line) {
            return map.ignoreLinesContaining().stream().noneMatch(t -> TextMatch.containsText(line, t));
        }

        Extraction execute() {
            for (int i = 0; i < lines.size(); i++) {
                TextLine line = lines.get(i);
                if (inTable && line.page() != lastPage) {
                    pagePreamble = true;
                }
                lastPage = line.page();
                int consumed = tryHeader(i);
                if (consumed > 0) {
                    i += consumed - 1;
                    continue;
                }
                boolean tableEnd = inTable && endLabels.stream().anyMatch(l -> TextMatch.contains(line, l));
                if (!tableEnd && map.debtor().source() == DebtorSource.HEADER && map.debtor().blockStartLabel() != null
                        && TextMatch.contains(line, map.debtor().blockStartLabel())) {
                    // o título em aberto continua: a sua linha seguinte pode vir depois do cabeçalho repetido
                    inTable = false;
                    pagePreamble = true;
                    blocks.add(new BlockBuilder(line.ref()));
                    readLabelFields(i);
                    continue;
                }
                if (inTable) {
                    tableLine(line);
                } else {
                    readLabelFields(i);
                    readControls(line);
                }
            }
            closeRecord();
            return build();
        }

        /** @return linhas consumidas pelo cabeçalho (0 = a linha não é cabeçalho) */
        private int tryHeader(int i) {
            if (table.headerLines().isEmpty()) {
                return 0;
            }
            TextLine line = lines.get(i);
            List<Zone> first = zonesFromHeader(line, table.headerLines().getFirst(), columnsByLine.getOrDefault(1, List.of()));
            if (first == null) {
                return 0;
            }
            zones.clear();
            zones.put(1, first);
            SourceRef region = line.ref();
            int consumed = 1;
            for (int k = 2; k <= table.headerLines().size(); k++) {
                if (i + consumed >= lines.size()) {
                    break;
                }
                TextLine next = lines.get(i + consumed);
                List<Zone> z = next.page() == line.page()
                        ? zonesFromHeader(next, table.headerLines().get(k - 1), columnsByLine.getOrDefault(k, List.of()))
                        : null;
                if (z == null) {
                    break;
                }
                zones.put(k, z);
                region = region.union(next.ref());
                consumed++;
            }
            for (int k = 2; k <= table.linesPerRecord(); k++) {
                zones.computeIfAbsent(k, key -> fallbackZones(columnsByLine.getOrDefault(key, List.of())));
            }
            headerRegions.add(region);
            inTable = true;
            pagePreamble = false;
            headerFound = true;
            return consumed;
        }

        private void tableLine(TextLine line) {
            if (endLabels.stream().anyMatch(l -> TextMatch.contains(line, l))) {
                closeRecord();
                readControls(line);
                inTable = false;
                return;
            }
            Map<String, CellValue> first = cells(line, zones.get(1));
            boolean anchor = table.anchorColumn() == null
                    ? table.linesPerRecord() == 1 && !first.isEmpty()
                    : anchorAccepts(first.get(table.anchorColumn()));
            if (anchor) {
                closeRecord();
                pending = new RecordBuilder(currentBlock(), line, first);
                pagePreamble = false;
                return;
            }
            if (pagePreamble) {
                notes.add(new LineNote(line.ref(), "pdf.line_page_preamble"));
                return;
            }
            if (pending != null && pending.lineCount < table.linesPerRecord()) {
                pending.add(line, cells(line, zones.get(pending.lineCount + 1)));
                return;
            }
            notes.add(new LineNote(line.ref(), "pdf.line_outside_record"));
        }

        /** A âncora está preenchida e o texto é do tipo do destino da coluna (data ou valor). */
        private boolean anchorAccepts(CellValue cell) {
            if (cell == null || cell.isBlank()) {
                return false;
            }
            List<String> targets = table.column(table.anchorColumn()).map(TableColumn::targets).orElse(List.of());
            for (String target : targets) {
                java.util.Optional<CanonicalField> field = CanonicalField.byColumn(target);
                if (field.isPresent() && DATE_FIELDS.contains(field.get())) {
                    return BrazilianFormats.parseDate(cell.text()).isPresent();
                }
                if (field.isPresent() && MonetaryFields.ALL.contains(field.get())) {
                    return CanonicalAssembler.canonicalAmount(cell.text(), map.numberFormat()).isPresent();
                }
            }
            return true;
        }

        private int currentBlock() {
            if (blocks.isEmpty()) {
                blocks.add(new BlockBuilder(null));
            }
            return blocks.size() - 1;
        }

        private void closeRecord() {
            if (pending != null) {
                records.add(pending);
                pending = null;
            }
        }

        private void readLabelFields(int i) {
            TextLine line = lines.get(i);
            TextLine next = i + 1 < lines.size() ? lines.get(i + 1) : null;
            if (map.debtor().source() == DebtorSource.HEADER && !blocks.isEmpty()) {
                BlockBuilder block = blocks.getLast();
                for (LabelField f : map.debtor().fields()) {
                    if (f.target() != null && !block.fields.containsKey(f.target())) {
                        read(line, next, f).ifPresent(v -> block.fields.put(f.target(), v));
                    }
                }
            }
            for (LabelField f : map.documentFields()) {
                if (f.target() != null && !documentFields.containsKey(f.target())) {
                    read(line, next, f).ifPresent(v -> documentFields.put(f.target(), v));
                }
            }
        }

        private java.util.Optional<CellValue> read(TextLine line, TextLine next, LabelField f) {
            int at = TextMatch.find(line, f.label(), 0);
            if (at < 0) {
                return java.util.Optional.empty();
            }
            int size = TextMatch.size(f.label());
            List<TextWord> words = f.position() == ValuePosition.BELOW
                    ? valueBelow(line, at, size, next, knownLabels)
                    : valueRight(line, at + size, knownLabels);
            words = transform(words, f.transform());
            if (words.isEmpty()) {
                return java.util.Optional.empty();
            }
            TextLine source = f.position() == ValuePosition.BELOW ? next : line;
            return java.util.Optional.of(cell(source, words));
        }

        private void readControls(TextLine line) {
            Map<String, CellValue> cellsOfLine = null;
            for (ControlTotal c : map.controlTotals()) {
                int at = c.label() == null ? -1 : TextMatch.find(line, c.label(), 0);
                if (at < 0) {
                    continue;
                }
                CellValue value = null;
                if (c.column() != null) {
                    if (zones.containsKey(1)) {
                        if (cellsOfLine == null) {
                            cellsOfLine = cells(line, zones.get(1));
                        }
                        value = cellsOfLine.get(c.column());
                    }
                } else {
                    List<TextWord> words = valueRight(line, at + TextMatch.size(c.label()), knownLabels);
                    value = words.isEmpty() ? null : cell(line, words);
                }
                if (value != null && !value.isBlank()) {
                    controls.add(new PendingControl(blocks.size() - 1, c, value));
                }
            }
        }

        private Extraction build() {
            // une blocos consecutivos do mesmo devedor (cabeçalho repetido a cada página)
            int[] remap = new int[Math.max(1, blocks.size())];
            List<BlockBuilder> merged = new ArrayList<>();
            for (int b = 0; b < blocks.size(); b++) {
                BlockBuilder block = blocks.get(b);
                BlockBuilder last = merged.isEmpty() ? null : merged.getLast();
                if (last != null && block.sameDebtorAs(last)) {
                    block.fields.forEach(last.fields::putIfAbsent);
                } else {
                    merged.add(block);
                }
                remap[b] = merged.size() - 1;
            }
            List<DebtorBlock> debtors = new ArrayList<>();
            for (int b = 0; b < merged.size(); b++) {
                debtors.add(new DebtorBlock(b, merged.get(b).fields, merged.get(b).region));
            }
            List<ExtractedRecord> out = new ArrayList<>();
            int ordinal = 1;
            for (RecordBuilder r : records) {
                out.add(new ExtractedRecord(ordinal++, r.block < 0 ? 0 : remap[r.block], r.cells, r.lines));
            }
            List<ControlValue> controlValues = controls.stream()
                    .map(c -> new ControlValue(c.block < 0 ? -1 : remap[c.block], c.definition, c.value)).toList();
            return new Extraction(debtors, out, controlValues, documentFields, headerRegions, notes, headerFound);
        }
    }

    private record PendingControl(int block, ControlTotal definition, CellValue value) {
    }

    private static final class BlockBuilder {

        final Map<String, CellValue> fields = new LinkedHashMap<>();
        final SourceRef region;

        BlockBuilder(SourceRef region) {
            this.region = region;
        }

        boolean sameDebtorAs(BlockBuilder other) {
            String mine = key();
            return mine != null && Objects.equals(mine, other.key());
        }

        private String key() {
            CellValue document = fields.get("documento");
            if (document != null && !document.isBlank()) {
                String digits = document.text().replaceAll("\\D", "");
                return digits.isEmpty() ? null : "doc:" + digits;
            }
            CellValue name = fields.get("nome");
            return name == null || name.isBlank() ? null : "nome:" + TextMatch.normalize(name.text());
        }
    }

    private static final class RecordBuilder {

        final int block;
        final Map<String, CellValue> cells = new LinkedHashMap<>();
        final List<SourceRef> lines = new ArrayList<>();
        int lineCount;

        RecordBuilder(int block, TextLine first, Map<String, CellValue> cells) {
            this.block = block;
            add(first, cells);
        }

        void add(TextLine line, Map<String, CellValue> lineCells) {
            lineCells.forEach(cells::putIfAbsent);
            lines.add(line.ref());
            lineCount++;
        }
    }
}
