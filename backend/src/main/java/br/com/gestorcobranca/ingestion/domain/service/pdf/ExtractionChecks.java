package br.com.gestorcobranca.ingestion.domain.service.pdf;

import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.RowIssue;
import br.com.gestorcobranca.ingestion.domain.model.SourceRow;
import br.com.gestorcobranca.ingestion.domain.model.format.AmountReading;
import br.com.gestorcobranca.ingestion.domain.model.format.FormatOption;
import br.com.gestorcobranca.ingestion.domain.model.pdf.CanonicalRecord;
import br.com.gestorcobranca.ingestion.domain.model.pdf.CellValue;
import br.com.gestorcobranca.ingestion.domain.model.pdf.CheckReport;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ControlValue;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractedRecord;
import br.com.gestorcobranca.ingestion.domain.model.pdf.Extraction;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.ControlTotal;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.NumberStyle;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap.TotalScope;
import br.com.gestorcobranca.ingestion.domain.model.pdf.Finding;
import br.com.gestorcobranca.ingestion.domain.model.pdf.LineNote;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfText;
import br.com.gestorcobranca.ingestion.domain.model.pdf.RecordOrigin;
import br.com.gestorcobranca.ingestion.domain.model.pdf.SourceRef;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TextLine;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TextPage;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TextWord;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TotalsCheck;
import br.com.gestorcobranca.ingestion.domain.service.BrazilianFormats;
import br.com.gestorcobranca.ingestion.domain.service.RowValidation;
import br.com.gestorcobranca.ingestion.domain.service.RowValidator;
import br.com.gestorcobranca.ingestion.domain.service.format.AmountParser;
import br.com.gestorcobranca.ingestion.domain.service.review.Brl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Verificações automáticas antes da tela de validação:
 * <ol>
 *   <li><strong>Totais de controle</strong>: cada total impresso no documento × a soma da coluna (ou do campo) dos
 *       títulos extraídos no bloco do devedor (ou no documento), e a quantidade de títulos quando houver. Diferença
 *       acima de {@link #AMOUNT_TOLERANCE} <strong>bloqueia</strong> até o mapa ser corrigido ou a divergência
 *       justificada;</li>
 *   <li><strong>Rastreio de origem</strong>: todo valor lido do texto é conferido de novo contra as palavras da região
 *       de origem (página, linha e caixa) — um valor que não aparece literalmente no texto bloqueia;</li>
 *   <li><strong>Linhas</strong>: o mesmo {@link RowValidator} do CSV/XLSX (CPF/CNPJ com dígito verificador, datas,
 *       valores, obrigatórios e títulos repetidos), sem regra duplicada;</li>
 *   <li><strong>Leitura visual</strong>: registros com confiança abaixo de {@code minConfidence} pedem conferência um a
 *       um;</li>
 *   <li>linhas da tabela não reconhecidas e totais do mapa ausentes no documento viram avisos.</li>
 * </ol>
 * Alertas de ticket médio e de separador decimal continuam no assistente de conferência do ingestion (passo 5), que
 * recebe o lote gerado a partir daqui.
 */
public final class ExtractionChecks {

    public static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.01");
    private static final Predicate<CanonicalRecord> ALL = r -> true;

    private final RowValidator rowValidator;
    private final double minConfidence;

    /** @param today data de hoje no fuso de operação (regras de data do validador) */
    public ExtractionChecks(LocalDate today, double minConfidence) {
        this.rowValidator = new RowValidator(today);
        this.minConfidence = minConfidence;
    }

    /**
     * @param text       camada de texto (nula na leitura visual)
     * @param extraction resultado do intérprete (nulo na leitura visual)
     * @param controls   totais encontrados no documento (do intérprete ou da leitura visual)
     */
    public CheckReport check(PdfText text, ExtractionMap map, Extraction extraction, List<CanonicalRecord> records,
                             List<ControlValue> controls) {
        List<Finding> findings = new ArrayList<>();
        List<TotalsCheck> totals = new ArrayList<>();
        if (extraction != null && !extraction.tableHeaderFound()) {
            findings.add(Finding.blocking("pdf.table_header_not_found",
                    "O cabeçalho da tabela descrito no mapa não foi encontrado no documento", null, null, null));
        }
        if (records.isEmpty()) {
            findings.add(Finding.blocking("pdf.no_records", "Nenhum título encontrado com este mapa", null, null, null));
        }
        totals(map, extraction, records, controls, findings, totals);
        if (text != null) {
            trace(text, records, findings);
        }
        if (extraction != null && !extraction.unrecognized().isEmpty()) {
            for (LineNote note : extraction.unrecognized()) {
                findings.add(Finding.warning(note.code(), note.code().equals("pdf.line_page_preamble")
                        ? "Linha no topo da página dentro da tabela, ignorada"
                        : "Linha da tabela fora de um título (confira as linhas por título e a coluna-âncora)",
                        null, null, note.source()));
            }
        }
        visual(records, findings);
        rows(records, findings);
        return new CheckReport(findings, totals);
    }

    // ------------------------------------------------------------------ totais

    private void totals(ExtractionMap map, Extraction extraction, List<CanonicalRecord> records,
                        List<ControlValue> controls, List<Finding> findings, List<TotalsCheck> totals) {
        if (map.controlTotals().isEmpty()) {
            findings.add(Finding.warning("pdf.no_control_totals",
                    "O mapa não indica totais de controle: confira a soma na conferência de grandes totais", null, null,
                    null));
        }
        for (ControlTotal definition : map.controlTotals()) {
            if (controls.stream().noneMatch(c -> c.definition().equals(definition))) {
                findings.add(Finding.warning("pdf.control_not_found",
                        "Total de controle \"" + definition.label() + "\" não encontrado no documento", null, null, null));
            }
        }
        Map<Integer, ExtractedRecord> byOrdinal = new HashMap<>();
        if (extraction != null) {
            extraction.records().forEach(r -> byOrdinal.put(r.ordinal(), r));
        }
        for (ControlValue control : controls) {
            ControlTotal d = control.definition();
            Predicate<CanonicalRecord> scope = d.scope() == TotalScope.DOCUMENT || control.debtorIndex() < 0 ? ALL
                    : r -> r.debtorIndex() == control.debtorIndex();
            List<CanonicalRecord> inScope = records.stream().filter(scope).toList();
            BigDecimal document = d.count()
                    ? BrazilianFormats.parseInteger(control.value().text().replaceAll("[^0-9]", "")).map(BigDecimal::valueOf).orElse(null)
                    : amount(control.value().text(), map.numberFormat());
            if (document == null) {
                findings.add(Finding.blocking("pdf.control_unreadable",
                        "Não foi possível ler o total \"" + d.label() + "\" do documento", null, null,
                        control.value().source()));
                totals.add(new TotalsCheck(scopeIndex(control), d.label(), d.column(), d.target(), null, null, null,
                        false, inScope.size(), control.value().source()));
                continue;
            }
            BigDecimal extracted = d.count() ? BigDecimal.valueOf(inScope.size())
                    : sum(inScope, d, byOrdinal, map.numberFormat());
            BigDecimal difference = document.subtract(extracted);
            boolean matches = d.count() ? difference.signum() == 0 : difference.abs().compareTo(AMOUNT_TOLERANCE) <= 0;
            totals.add(new TotalsCheck(scopeIndex(control), d.label(), d.column(), d.target(), document, extracted,
                    difference, matches, inScope.size(), control.value().source()));
            if (!matches) {
                findings.add(Finding.blocking("pdf.totals_mismatch", d.count()
                                ? "Quantidade de títulos do documento (%s) diferente da extraída (%s)".formatted(document, extracted)
                                : "Total \"%s\"%s do documento (%s) diferente da soma extraída (%s)".formatted(d.label(),
                                d.column() == null ? "" : " da coluna " + d.column(), Brl.format(document), Brl.format(extracted)),
                        null, d.target(), control.value().source()));
            }
        }
    }

    private static int scopeIndex(ControlValue control) {
        return control.definition().scope() == TotalScope.DOCUMENT ? -1 : control.debtorIndex();
    }

    /** Soma da coluna (texto do PDF) ou, sem coluna/na leitura visual, do campo canônico. */
    private static BigDecimal sum(List<CanonicalRecord> records, ControlTotal d, Map<Integer, ExtractedRecord> byOrdinal,
                                  NumberStyle style) {
        BigDecimal total = BigDecimal.ZERO;
        for (CanonicalRecord r : records) {
            String raw;
            if (d.column() != null && r.origin() == RecordOrigin.TEXT && byOrdinal.containsKey(r.ordinal())) {
                CellValue cell = byOrdinal.get(r.ordinal()).cells().get(d.column());
                raw = cell == null ? null : cell.text();
            } else {
                raw = CanonicalField.byColumn(d.target()).map(f -> r.values().get(f)).orElse(null);
                if (raw != null && CanonicalField.byColumn(d.target()).map(r.typed()::contains).orElse(false)) {
                    total = total.add(new BigDecimal(raw));
                    continue;
                }
            }
            BigDecimal value = raw == null || raw.isBlank() ? null : amount(raw, style);
            if (value != null) {
                total = total.add(value);
            }
        }
        return total;
    }

    static BigDecimal amount(String raw, NumberStyle style) {
        AmountReading reading = AmountParser.read(raw, style == NumberStyle.DOT ? FormatOption.DOT : FormatOption.COMMA);
        return reading.ok() ? reading.value() : null;
    }

    // ------------------------------------------------------------------ rastreio

    /** Confere de novo cada valor contra as palavras da região de origem. */
    private static void trace(PdfText text, List<CanonicalRecord> records, List<Finding> findings) {
        Map<Integer, Map<Integer, TextLine>> index = new HashMap<>();
        for (TextPage page : text.pages()) {
            Map<Integer, TextLine> lines = new HashMap<>();
            page.lines().forEach(l -> lines.put(l.number(), l));
            index.put(page.number(), lines);
        }
        for (CanonicalRecord r : records) {
            if (r.origin() != RecordOrigin.TEXT) {
                continue;
            }
            r.raw().forEach((field, raw) -> {
                List<SourceRef> refs = r.sources().getOrDefault(field, List.of());
                List<String> parts = new ArrayList<>();
                for (SourceRef ref : refs) {
                    parts.add(textAt(index, ref));
                }
                if (refs.isEmpty() || !String.join(CanonicalAssembler.JOIN, parts).equals(raw)) {
                    findings.add(Finding.blocking("pdf.value_not_in_text",
                            "Valor do campo " + field + " não confere com o texto do PDF na origem indicada", r.ordinal(),
                            field, refs.isEmpty() ? null : refs.getFirst()));
                }
            });
        }
    }

    private static String textAt(Map<Integer, Map<Integer, TextLine>> index, SourceRef ref) {
        TextLine line = index.getOrDefault(ref.page(), Map.of()).get(ref.line());
        if (line == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (TextWord w : line.words()) {
            double cx = w.center();
            if (cx >= ref.x0() - 0.1 && cx <= ref.x1() + 0.1) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(w.text());
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ leitura visual e linhas

    private void visual(List<CanonicalRecord> records, List<Finding> findings) {
        long visual = records.stream().filter(r -> r.origin() == RecordOrigin.VISUAL).count();
        if (visual == 0) {
            return;
        }
        findings.add(Finding.info("pdf.visual_reading",
                visual + " título(s) lidos visualmente (PDF sem camada de texto): confira cada valor com a imagem"));
        for (CanonicalRecord r : records) {
            if (r.origin() == RecordOrigin.VISUAL && (r.confidence() == null || r.confidence() < minConfidence)) {
                findings.add(Finding.warning("pdf.low_confidence",
                        "Leitura visual com confiança baixa: confirme ou exclua o título", r.ordinal(), null,
                        r.lines().isEmpty() ? null : r.lines().getFirst()));
            }
        }
    }

    private void rows(List<CanonicalRecord> records, List<Finding> findings) {
        Map<String, Long> firstByTitle = new HashMap<>();
        for (CanonicalRecord r : records) {
            SourceRow row = r.toSourceRow();
            String key = RowValidator.titleKey(row);
            Long duplicateOf = key == null ? null : firstByTitle.get(key);
            if (key != null) {
                firstByTitle.putIfAbsent(key, (long) r.ordinal());
            }
            RowValidation validation = rowValidator.validate(row, duplicateOf);
            for (RowIssue issue : validation.issues()) {
                SourceRef source = issue.field() == null ? null
                        : r.sources().getOrDefault(issue.field(), List.of()).stream().findFirst().orElse(null);
                findings.add(issue.isError()
                        ? Finding.error(issue.code(), issue.message(), r.ordinal(), issue.field(), source)
                        : Finding.warning(issue.code(), issue.message(), r.ordinal(), issue.field(), source));
            }
        }
    }
}
