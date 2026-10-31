package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.DebtorFacet;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.RecordFacets;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.RecordFilter;
import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.pdf.CanonicalRecord;
import br.com.gestorcobranca.ingestion.domain.model.pdf.Finding;
import br.com.gestorcobranca.ingestion.domain.model.pdf.RecordDecisions;
import br.com.gestorcobranca.ingestion.domain.service.IssueValueMasker;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Filtro dos títulos de uma importação PDF (situação, devedor e texto livre), o mesmo para a listagem, os contadores e
 * a decisão em massa pelo critério do filtro — o que o usuário vê é exatamente o que a ação alcança.
 * <p>
 * Situações: {@code EXCLUDED} (excluídos do lote); fora dos excluídos, {@code ERROR} (alguma ocorrência de erro),
 * {@code WARNING} (aviso, ou leitura visual de confiança baixa), {@code OK} (nada a observar) e, à parte, {@code
 * LOW_CONFIDENCE} (leitura visual de confiança baixa ainda não confirmada; também conta em WARNING ou ERROR).
 */
final class PdfRecordQuery {

    static final List<String> STATUSES = List.of("ALL", "OK", "WARNING", "ERROR", "LOW_CONFIDENCE", "EXCLUDED");
    static final int QUERY_MAX = 80;
    private static final Map<String, String> ALIASES = Map.of("ERRORS", "ERROR", "WARNINGS", "WARNING");

    private final List<CanonicalRecord> records;
    private final RecordDecisions decisions;
    private final Map<Integer, List<Finding>> findings;
    private final Predicate<CanonicalRecord> lowConfidence;
    private final boolean personal;

    /**
     * @param personal quem consulta vê dado pessoal (o texto livre também procura no nome e no CPF/CNPJ)
     */
    PdfRecordQuery(List<CanonicalRecord> records, List<Finding> findings, RecordDecisions decisions,
                   Predicate<CanonicalRecord> lowConfidence, boolean personal) {
        this.records = records;
        this.decisions = decisions;
        this.findings = findings.stream().filter(f -> f.record() != null)
                .collect(Collectors.groupingBy(Finding::record, LinkedHashMap::new, Collectors.toList()));
        this.lowConfidence = lowConfidence;
        this.personal = personal;
    }

    Map<Integer, List<Finding>> findingsByRecord() {
        return findings;
    }

    /** Situação normalizada (aceita os nomes antigos ERRORS e WARNINGS); vazia = ALL. */
    static String status(String raw) {
        if (raw == null || raw.isBlank()) {
            return "ALL";
        }
        String s = raw.strip().toUpperCase(Locale.ROOT);
        s = ALIASES.getOrDefault(s, s);
        if (!STATUSES.contains(s)) {
            throw new BusinessRuleException("pdf_import.filter_invalid", "Filtro inválido: " + raw);
        }
        return s;
    }

    static void validate(RecordFilter filter) {
        if (filter == null) {
            return;
        }
        status(filter.status());
        if (filter.query() != null && filter.query().length() > QUERY_MAX) {
            throw new BusinessRuleException("pdf_import.filter_invalid", "Texto de busca com mais de " + QUERY_MAX
                    + " caracteres");
        }
        if (filter.debtor() != null && filter.debtor() < 1) {
            throw new BusinessRuleException("pdf_import.filter_invalid", "Devedor inválido: " + filter.debtor());
        }
    }

    /** Títulos do filtro, na ordem do documento. */
    List<CanonicalRecord> select(RecordFilter filter) {
        RecordFilter f = filter == null ? RecordFilter.all() : filter;
        validate(f);
        String status = status(f.status());
        return records.stream().filter(r -> scope(r, f) && matches(r, status)).toList();
    }

    RecordFacets facets(RecordFilter filter) {
        RecordFilter f = filter == null ? RecordFilter.all() : filter;
        validate(f);
        List<CanonicalRecord> inScope = records.stream().filter(r -> scope(r, f)).toList();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (String s : STATUSES) {
            byStatus.put(s, (int) inScope.stream().filter(r -> matches(r, s)).count());
        }
        Map<Integer, List<CanonicalRecord>> byDebtor = records.stream()
                .collect(Collectors.groupingBy(CanonicalRecord::debtorIndex, java.util.TreeMap::new, Collectors.toList()));
        List<DebtorFacet> debtors = new ArrayList<>();
        byDebtor.forEach((index, list) -> {
            String name = list.getFirst().values().get(CanonicalField.DEBTOR_NAME);
            debtors.add(new DebtorFacet(index, name == null ? null
                    : personal ? name : IssueValueMasker.excerpt(CanonicalField.DEBTOR_NAME, name), list.size()));
        });
        return new RecordFacets(byStatus, debtors);
    }

    boolean matches(CanonicalRecord r, String status) {
        boolean excluded = decisions.isExcluded(r.ordinal());
        List<Finding> own = findings.getOrDefault(r.ordinal(), List.of());
        boolean error = own.stream().anyMatch(x -> x.severity() == Finding.Severity.ERROR);
        boolean low = lowConfidence.test(r);
        boolean warning = !error && (low || own.stream().anyMatch(x -> x.severity() == Finding.Severity.WARNING));
        return switch (status) {
            case "ALL" -> true;
            case "EXCLUDED" -> excluded;
            case "ERROR" -> !excluded && error;
            case "WARNING" -> !excluded && warning;
            case "OK" -> !excluded && !error && !warning;
            case "LOW_CONFIDENCE" -> !excluded && low && !decisions.isConfirmed(r.ordinal());
            default -> throw new IllegalArgumentException(status);
        };
    }

    private boolean scope(CanonicalRecord r, RecordFilter f) {
        if (f.debtor() != null && r.debtorIndex() != f.debtor()) {
            return false;
        }
        String q = normalize(f.query());
        if (q.isEmpty()) {
            return true;
        }
        Set<CanonicalField> fields = personal
                ? Set.of(CanonicalField.TITLE_EXTERNAL_ID, CanonicalField.CONTRACT_NUMBER, CanonicalField.DEBTOR_NAME,
                CanonicalField.DEBTOR_DOCUMENT)
                : Set.of(CanonicalField.TITLE_EXTERNAL_ID, CanonicalField.CONTRACT_NUMBER);
        String digits = q.replaceAll("\\D", "");
        for (CanonicalField field : fields) {
            String value = r.values().get(field);
            if (value == null) {
                continue;
            }
            if (normalize(value).contains(q)) {
                return true;
            }
            if (field == CanonicalField.DEBTOR_DOCUMENT && digits.length() >= 3
                    && value.replaceAll("\\D", "").contains(digits)) {
                return true;
            }
        }
        return false;
    }

    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").strip();
    }
}
