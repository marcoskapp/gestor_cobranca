package br.com.gestorcobranca.ingestion.domain.service.pdf;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.format.MonetaryFields;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.ControlTotal;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.DebtorSource;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.LabelField;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.TableColumn;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.TableSection;
import br.com.gestorcobranca.ingestion.domain.model.pdf.MapProblem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validação estrutural e semântica de um mapa de extração, aplicada a toda proposta da IA e a toda edição do usuário
 * antes de o mapa ser aplicado: limites de tamanho, rótulos e colunas coerentes com o cabeçalho, destinos conhecidos
 * do layout canônico e cobertura dos campos obrigatórios do ingestion.
 */
public final class ExtractionMapValidator {

    public static final int MAX_LABEL = 80;
    public static final int MAX_COLUMNS = 80;
    public static final int MAX_FIELDS = 40;
    public static final int MAX_LIST = 20;
    static final int MIN_IGNORE_TEXT = 3;
    private static final Pattern INFO = Pattern.compile("info:[a-z0-9_]{1,40}");
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");
    /** Campos obrigatórios do layout canônico (sem eles o validador do ingestion recusa todas as linhas). */
    public static final List<CanonicalField> REQUIRED = List.of(CanonicalField.values()).stream()
            .filter(CanonicalField::required).toList();

    public List<MapProblem> validate(ExtractionMap map) {
        List<MapProblem> problems = new ArrayList<>();
        if (map == null) {
            problems.add(new MapProblem("pdf_map.missing", "", "Mapa ausente"));
            return problems;
        }
        if (map.schemaVersion() != ExtractionMap.SCHEMA_VERSION) {
            problems.add(new MapProblem("pdf_map.schema_version", "schemaVersion",
                    "Versão de esquema não suportada (esperada " + ExtractionMap.SCHEMA_VERSION + ")"));
        }
        text(problems, "documentType", map.documentType(), 120, false);
        Set<String> covered = new HashSet<>();
        TableSection table = map.table();
        List<String> lineOneColumns = new ArrayList<>();
        if (table == null || table.headerLines().isEmpty() || table.headerLines().getFirst().isEmpty()) {
            problems.add(new MapProblem("pdf_map.table_header_missing", "table.headerLines",
                    "Informe os rótulos do cabeçalho da tabela de títulos"));
        } else {
            table(problems, table, covered, lineOneColumns);
        }
        debtor(problems, map, covered);
        List<LabelField> documentFields = map.documentFields();
        if (documentFields.size() > MAX_FIELDS) {
            problems.add(new MapProblem("pdf_map.too_many", "documentFields", "Campos do documento demais"));
        }
        for (int i = 0; i < documentFields.size(); i++) {
            labelField(problems, "documentFields[" + i + "]", documentFields.get(i), covered);
        }
        controls(problems, map.controlTotals(), lineOneColumns);
        List<String> ignore = map.ignoreLinesContaining();
        if (ignore.size() > MAX_LIST) {
            problems.add(new MapProblem("pdf_map.too_many", "ignoreLinesContaining", "Textos a ignorar demais"));
        }
        for (int i = 0; i < ignore.size(); i++) {
            String t = ignore.get(i);
            if (t == null || t.strip().length() < MIN_IGNORE_TEXT) {
                problems.add(new MapProblem("pdf_map.ignore_text_short", "ignoreLinesContaining[" + i + "]",
                        "Texto a ignorar precisa de ao menos " + MIN_IGNORE_TEXT + " caracteres"));
            } else {
                text(problems, "ignoreLinesContaining[" + i + "]", t, MAX_LABEL, true);
            }
        }
        for (CanonicalField required : REQUIRED) {
            if (!covered.contains(required.column())) {
                problems.add(new MapProblem("pdf_map.required_missing", required.column(),
                        "Campo obrigatório sem origem no mapa: " + required.column()));
            }
        }
        return problems;
    }

    private void table(List<MapProblem> problems, TableSection table, Set<String> covered, List<String> lineOneColumns) {
        if (table.linesPerRecord() < 1 || table.linesPerRecord() > TableSection.MAX_LINES_PER_RECORD) {
            problems.add(new MapProblem("pdf_map.lines_per_record", "table.linesPerRecord",
                    "Cada título deve ocupar de 1 a " + TableSection.MAX_LINES_PER_RECORD + " linhas"));
        }
        if (table.headerLines().size() > Math.max(1, table.linesPerRecord())) {
            problems.add(new MapProblem("pdf_map.header_lines", "table.headerLines",
                    "O cabeçalho tem mais linhas do que cada título"));
        }
        Set<String> labels = new HashSet<>();
        for (int l = 0; l < table.headerLines().size(); l++) {
            List<String> line = table.headerLines().get(l);
            if (line.size() > MAX_COLUMNS) {
                problems.add(new MapProblem("pdf_map.too_many", "table.headerLines[" + l + "]", "Colunas demais"));
            }
            for (int c = 0; c < line.size(); c++) {
                String path = "table.headerLines[" + l + "][" + c + "]";
                if (text(problems, path, line.get(c), MAX_LABEL, true)
                        && !labels.add(TextMatch.normalize(line.get(c)))) {
                    problems.add(new MapProblem("pdf_map.header_duplicated", path,
                            "Rótulo repetido no cabeçalho: " + line.get(c)));
                }
            }
        }
        List<TableColumn> columns = table.columns();
        if (columns.size() > MAX_COLUMNS) {
            problems.add(new MapProblem("pdf_map.too_many", "table.columns", "Colunas demais"));
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < columns.size(); i++) {
            TableColumn c = columns.get(i);
            String path = "table.columns[" + i + "]";
            if (!text(problems, path + ".header", c.header(), MAX_LABEL, true)) {
                continue;
            }
            if (!seen.add(c.header())) {
                problems.add(new MapProblem("pdf_map.column_duplicated", path, "Coluna repetida: " + c.header()));
            }
            if (c.line() > Math.max(1, table.linesPerRecord())) {
                problems.add(new MapProblem("pdf_map.column_line", path + ".line",
                        "A coluna " + c.header() + " está numa linha que o título não tem"));
            }
            boolean inHeader = c.line() <= table.headerLines().size()
                    && table.headerLines().get(c.line() - 1).contains(c.header());
            boolean explicit = c.xMin() != null && c.xMax() != null;
            long sameLine = columns.stream().filter(o -> o.line() == c.line()).count();
            if (!inHeader && !explicit && !(c.line() > table.headerLines().size() && sameLine == 1)) {
                problems.add(new MapProblem("pdf_map.column_not_in_header", path + ".header",
                        "A coluna " + c.header() + " não está no cabeçalho da linha " + c.line()
                                + " (informe os limites xMin e xMax)"));
            }
            if (c.xMin() != null && c.xMax() != null && c.xMin() >= c.xMax()) {
                problems.add(new MapProblem("pdf_map.column_bounds", path, "xMin deve ser menor que xMax"));
            }
            if (c.line() == 1) {
                lineOneColumns.add(c.header());
            }
            for (int t = 0; t < c.targets().size(); t++) {
                String target = c.targets().get(t);
                if (target(problems, path + ".targets[" + t + "]", target, true)) {
                    covered.add(canonicalKey(target));
                }
            }
        }
        if (table.linesPerRecord() > 1 && (table.anchorColumn() == null || !lineOneColumns.contains(table.anchorColumn()))) {
            problems.add(new MapProblem("pdf_map.anchor_invalid", "table.anchorColumn",
                    "Com títulos de várias linhas, escolha uma coluna da 1ª linha sempre preenchida (ex.: vencimento)"));
        } else if (table.anchorColumn() != null && !lineOneColumns.contains(table.anchorColumn())) {
            problems.add(new MapProblem("pdf_map.anchor_invalid", "table.anchorColumn",
                    "A coluna-âncora precisa ser uma coluna da 1ª linha"));
        }
        if (table.endMarkers().size() > MAX_LIST) {
            problems.add(new MapProblem("pdf_map.too_many", "table.endMarkers", "Marcadores de fim demais"));
        }
        for (int i = 0; i < table.endMarkers().size(); i++) {
            text(problems, "table.endMarkers[" + i + "]", table.endMarkers().get(i), MAX_LABEL, true);
        }
    }

    private void debtor(List<MapProblem> problems, ExtractionMap map, Set<String> covered) {
        ExtractionMap.DebtorSection debtor = map.debtor();
        if (debtor.source() == DebtorSource.HEADER) {
            text(problems, "debtor.blockStartLabel", debtor.blockStartLabel(), MAX_LABEL, true);
            if (debtor.fields().isEmpty()) {
                problems.add(new MapProblem("pdf_map.debtor_fields_missing", "debtor.fields",
                        "Informe os campos do cabeçalho do devedor"));
            }
        }
        if (debtor.fields().size() > MAX_FIELDS) {
            problems.add(new MapProblem("pdf_map.too_many", "debtor.fields", "Campos do devedor demais"));
        }
        for (int i = 0; i < debtor.fields().size(); i++) {
            labelField(problems, "debtor.fields[" + i + "]", debtor.fields().get(i), covered);
        }
    }

    private void labelField(List<MapProblem> problems, String path, LabelField f, Set<String> covered) {
        if (f == null) {
            problems.add(new MapProblem("pdf_map.field_missing", path, "Campo vazio"));
            return;
        }
        text(problems, path + ".label", f.label(), MAX_LABEL, true);
        if (target(problems, path + ".target", f.target(), false)) {
            covered.add(canonicalKey(f.target()));
        }
    }

    private void controls(List<MapProblem> problems, List<ControlTotal> controls, List<String> lineOneColumns) {
        if (controls.size() > MAX_LIST) {
            problems.add(new MapProblem("pdf_map.too_many", "controlTotals", "Totais de controle demais"));
        }
        for (int i = 0; i < controls.size(); i++) {
            ControlTotal c = controls.get(i);
            String path = "controlTotals[" + i + "]";
            if (c == null) {
                problems.add(new MapProblem("pdf_map.field_missing", path, "Total vazio"));
                continue;
            }
            text(problems, path + ".label", c.label(), MAX_LABEL, true);
            if (c.column() != null && !lineOneColumns.contains(c.column())) {
                problems.add(new MapProblem("pdf_map.control_column", path + ".column",
                        "O total aponta uma coluna que não está na 1ª linha do título: " + c.column()));
            }
            boolean monetary = c.target() != null && MonetaryFields.byColumn(c.target()).isPresent();
            if (!c.count() && !monetary) {
                problems.add(new MapProblem("pdf_map.control_target", path + ".target",
                        "Total de controle deve somar um valor (valor_principal, saldo…) ou contar títulos (quantidade)"));
            }
        }
    }

    /** @return o destino é válido (e não vazio) */
    private boolean target(List<MapProblem> problems, String path, String target, boolean tableColumn) {
        if (target == null || target.isBlank()) {
            problems.add(new MapProblem("pdf_map.target_invalid", path, "Destino vazio"));
            return false;
        }
        if (INFO.matcher(target).matches()) {
            return true;
        }
        if (ExtractionMap.PAID_WHEN_FILLED.equals(target)) {
            if (!tableColumn) {
                problems.add(new MapProblem("pdf_map.target_invalid", path,
                        ExtractionMap.PAID_WHEN_FILLED + " só vale para colunas da tabela"));
                return false;
            }
            return true;
        }
        if (CanonicalField.byColumn(target).isEmpty() || !target.equals(target.strip().toLowerCase(java.util.Locale.ROOT))) {
            problems.add(new MapProblem("pdf_map.target_invalid", path, "Destino desconhecido: " + target));
            return false;
        }
        return true;
    }

    private static String canonicalKey(String target) {
        return CanonicalField.byColumn(target).map(CanonicalField::column).orElse(target);
    }

    /** @return o texto é válido */
    private static boolean text(List<MapProblem> problems, String path, String value, int max, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                problems.add(new MapProblem("pdf_map.text_missing", path, "Texto obrigatório"));
            }
            return false;
        }
        if (value.length() > max) {
            problems.add(new MapProblem("pdf_map.text_too_long", path, "Texto com mais de " + max + " caracteres"));
            return false;
        }
        if (CONTROL_CHARS.matcher(value).find()) {
            problems.add(new MapProblem("pdf_map.text_invalid", path, "Texto com caracteres de controle"));
            return false;
        }
        return true;
    }
}
