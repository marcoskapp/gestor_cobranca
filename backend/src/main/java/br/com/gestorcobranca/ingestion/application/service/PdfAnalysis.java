package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.application.PdfImportProperties;
import br.com.gestorcobranca.ingestion.application.port.out.PdfDocumentReader;
import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.pdf.CanonicalRecord;
import br.com.gestorcobranca.ingestion.domain.model.pdf.CheckReport;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ControlValue;
import br.com.gestorcobranca.ingestion.domain.model.pdf.Extraction;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap;
import br.com.gestorcobranca.ingestion.domain.model.pdf.Finding;
import br.com.gestorcobranca.ingestion.domain.model.pdf.MapOrigin;
import br.com.gestorcobranca.ingestion.domain.model.pdf.MapProblem;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfImport;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfText;
import br.com.gestorcobranca.ingestion.domain.model.pdf.RecordDecisions;
import br.com.gestorcobranca.ingestion.domain.model.pdf.RecordOrigin;

import br.com.gestorcobranca.ingestion.domain.model.pdf.VisualReading;
import br.com.gestorcobranca.ingestion.domain.service.pdf.CanonicalAssembler;
import br.com.gestorcobranca.ingestion.domain.service.pdf.ExtractionChecks;
import br.com.gestorcobranca.ingestion.domain.service.pdf.ExtractionMapValidator;
import br.com.gestorcobranca.ingestion.domain.service.pdf.MapInterpreter;
import br.com.gestorcobranca.ingestion.domain.service.pdf.VisualAssembler;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.storage.FileStorage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Análise de uma importação PDF com o mapa corrente: lê o arquivo guardado, aplica o mapa (intérprete determinístico)
 * ou converte a leitura visual cifrada, monta os títulos canônicos e roda as verificações. É recalculada a cada
 * consulta — o resultado depende só do arquivo, do mapa e das decisões, nunca de valores gravados. O texto posicionado
 * fica num cache curto em memória ({@link #TEXT_CACHE_TTL}, poucas entradas) para a navegação na tela.
 */
@Component
class PdfAnalysis {

    static final java.time.Duration TEXT_CACHE_TTL = java.time.Duration.ofMinutes(5);
    private static final int TEXT_CACHE_SIZE = 16;
    /** Verificações que bloqueiam mas aceitam justificativa (o documento não muda): totais. */
    static final Set<String> TOTALS_CODES = Set.of("pdf.totals_mismatch", "pdf.control_unreadable");

    private final PdfDocumentReader reader;
    private final FileStorage storage;
    private final JsonCodec json;
    private final PdfImportProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;
    private final MapInterpreter interpreter = new MapInterpreter();
    private final CanonicalAssembler assembler = new CanonicalAssembler();
    private final VisualAssembler visualAssembler = new VisualAssembler();
    private final ExtractionMapValidator validator = new ExtractionMapValidator();
    private final Map<String, CachedText> texts = new LinkedHashMap<>(TEXT_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedText> eldest) {
            return size() > TEXT_CACHE_SIZE;
        }
    };

    PdfAnalysis(PdfDocumentReader reader, FileStorage storage, JsonCodec json, PdfImportProperties properties,
                MeterRegistry meters, Clock clock) {
        this.reader = reader;
        this.storage = storage;
        this.json = json;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    private record CachedText(PdfText text, Instant at) {
    }

    /**
     * @param text       camada de texto (nula na leitura visual)
     * @param extraction resultado do intérprete (nulo na leitura visual ou sem mapa aplicável)
     */
    record Result(PdfText text, ExtractionMap map, List<MapProblem> mapProblems, Extraction extraction,
                  List<CanonicalRecord> records, List<ControlValue> controls, CheckReport report) {

        boolean totalsMatch() {
            return report.totalsMatch() && report.findings().stream().noneMatch(f -> TOTALS_CODES.contains(f.code()));
        }

        /** Bloqueios que nem a justificativa dos totais libera. */
        List<Finding> hardBlocking() {
            return report.findings().stream().filter(f -> f.blocking() && !TOTALS_CODES.contains(f.code())).toList();
        }
    }

    // ------------------------------------------------------------------ arquivo e texto

    byte[] content(PdfImport pdf) {
        if (pdf.getFileKey() == null) {
            throw new BusinessRuleException("pdf_import.file_deleted",
                    "O arquivo PDF já foi descartado pela política de retenção");
        }
        try (InputStream in = storage.open(pdf.getFileKey())) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o PDF guardado", e);
        }
    }

    PdfText text(PdfImport pdf) {
        String key = pdf.getTenantId() + ":" + pdf.getId() + ":" + pdf.getFileSha256();
        Instant now = clock.instant();
        synchronized (texts) {
            CachedText cached = texts.get(key);
            if (cached != null && cached.at().plus(TEXT_CACHE_TTL).isAfter(now)) {
                return cached.text();
            }
        }
        PdfText text = reader.readText(content(pdf));
        synchronized (texts) {
            texts.put(key, new CachedText(text, now));
        }
        return text;
    }

    /** Guarda no cache o texto já lido no recebimento (evita reler o arquivo logo em seguida). */
    void remember(PdfImport pdf, PdfText text) {
        synchronized (texts) {
            texts.put(pdf.getTenantId() + ":" + pdf.getId() + ":" + pdf.getFileSha256(), new CachedText(text, clock.instant()));
        }
    }

    void forget(PdfImport pdf) {
        synchronized (texts) {
            texts.remove(pdf.getTenantId() + ":" + pdf.getId() + ":" + pdf.getFileSha256());
        }
    }

    // ------------------------------------------------------------------ mapa

    ExtractionMap parseMap(String mapJson) {
        if (mapJson == null) {
            return null;
        }
        return json.fromJson(mapJson, ExtractionMap.class);
    }

    ExtractionMap parseMap(Map<String, Object> map) {
        try {
            return json.fromJson(json.toJson(map), ExtractionMap.class);
        } catch (RuntimeException e) {
            throw new BusinessRuleException("pdf_map.malformed", "Mapa fora do formato esperado (versão "
                    + ExtractionMap.SCHEMA_VERSION + ")");
        }
    }

    Map<String, Object> mapAsObject(String mapJson) {
        return mapJson == null ? null : json.toMap(mapJson);
    }

    String toJson(Object value) {
        return json.toJson(value);
    }

    RecordDecisions decisions(PdfImport pdf) {
        RecordDecisions d = json.fromJson(pdf.getDecisions(), RecordDecisions.class);
        return d == null ? RecordDecisions.none() : d;
    }

    // ------------------------------------------------------------------ análise

    /** Mapa corrente com as decisões correntes (títulos excluídos fora da soma dos totais de controle). */
    Result analyze(PdfImport pdf) {
        return analyze(pdf, parseMap(pdf.getMap()), decisions(pdf));
    }

    /** Mapa corrente sem as decisões do usuário (o mapa em si: salvar mapa para o credor). */
    Result analyzeMap(PdfImport pdf) {
        return analyze(pdf, parseMap(pdf.getMap()), RecordDecisions.none());
    }

    /** Mapa novo (as decisões valem por revisão: um mapa novo começa sem decisões). */
    Result analyze(PdfImport pdf, ExtractionMap map) {
        return analyze(pdf, map, RecordDecisions.none());
    }

    /** Mapa corrente com outras decisões (recálculo determinístico depois de uma decisão sobre os títulos). */
    Result analyze(PdfImport pdf, RecordDecisions decisions) {
        return analyze(pdf, parseMap(pdf.getMap()), decisions);
    }

    private Result analyze(PdfImport pdf, ExtractionMap map, RecordDecisions decisions) {
        Timer.Sample sample = Timer.start(meters);
        String outcome = "ok";
        try {
            ExtractionChecks checks = new ExtractionChecks(LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)),
                    properties.minConfidence());
            if (pdf.getMapOrigin() == MapOrigin.VISUAL) {
                if (pdf.getVisualResult() == null) {
                    throw new BusinessRuleException("pdf_import.file_deleted",
                            "A leitura visual já foi descartada pela política de retenção");
                }
                return visual(json.fromJson(pdf.getVisualResult(), VisualReading.class), map, checks,
                        decisions.excluded());
            }
            PdfText text = text(pdf);
            if (map == null) {
                return new Result(text, null, List.of(new MapProblem("pdf_map.missing", "", "Sem mapa")), null,
                        List.of(), List.of(), new CheckReport(List.of(Finding.blocking("pdf.map_invalid",
                        "Ainda não há mapa: escreva o mapa ou peça uma proposta à IA", null, null, null)), List.of()));
            }
            List<MapProblem> problems = validator.validate(map);
            Extraction extraction = interpreter.interpret(text, map);
            List<CanonicalRecord> records = assembler.assemble(extraction, map);
            CheckReport report = checks.check(text, map, extraction, records, extraction.controls(),
                    decisions.excluded());
            if (!problems.isEmpty()) {
                List<Finding> findings = new ArrayList<>();
                problems.forEach(p -> findings.add(Finding.blocking("pdf.map_invalid", p.message(), null, null, null)));
                findings.addAll(report.findings());
                report = new CheckReport(findings, report.totals());
            }
            return new Result(text, map, problems, extraction, records, extraction.controls(), report);
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            sample.stop(Timer.builder("ingestion.pdf.analysis.duration").tag("outcome", outcome)
                    .publishPercentiles(0.5, 0.95).register(meters));
        }
    }

    /** Leitura visual (PDF sem camada de texto): títulos e totais transcritos pelo modelo. */
    Result analyzeVisual(VisualReading reading, ExtractionMap described) {
        return visual(reading, described, new ExtractionChecks(LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)),
                properties.minConfidence()), Set.of());
    }

    private Result visual(VisualReading reading, ExtractionMap map, ExtractionChecks checks, Set<Integer> excluded) {
        List<CanonicalRecord> records = visualAssembler.records(reading);
        List<ControlValue> controls = visualAssembler.controls(reading);
        CheckReport report = checks.check(null, map, null, records, controls, excluded);
        return new Result(null, map, List.of(), null, records, controls, report);
    }

    // ------------------------------------------------------------------ resumo (sem dado pessoal)

    record Summary(int records, int recordsToImport, int excluded, int debtors, int errors, int warnings,
                   int recordsWithError, int unrecognizedLines, int lowConfidence, int confirmed,
                   BigDecimal principalTotal, BigDecimal balanceTotal, boolean totalsMatch, boolean blocking,
                   List<TotalLine> totals, Map<String, Long> codes) {
    }

    record TotalLine(int debtorIndex, String label, String column, String target, BigDecimal document,
                     BigDecimal extracted, boolean matches) {
    }

    Summary summary(Result r, RecordDecisions decisions) {
        int excluded = (int) r.records().stream().filter(c -> decisions.isExcluded(c.ordinal())).count();
        List<CanonicalRecord> toImport = r.records().stream().filter(c -> !decisions.isExcluded(c.ordinal())).toList();
        Set<Integer> withError = new java.util.HashSet<>();
        Map<String, Long> codes = new TreeMap<>();
        int errors = 0;
        int warnings = 0;
        for (Finding f : r.report().findings()) {
            codes.merge(f.code(), 1L, Long::sum);
            if (f.severity() == Finding.Severity.ERROR) {
                errors++;
                if (f.record() != null) {
                    withError.add(f.record());
                }
            } else if (f.severity() == Finding.Severity.WARNING) {
                warnings++;
            }
        }
        int lowConfidence = (int) toImport.stream().filter(c -> lowConfidence(c) && !decisions.isConfirmed(c.ordinal())).count();
        int unrecognized = r.extraction() == null ? 0 : r.extraction().unrecognized().size();
        long debtors = r.records().stream().map(CanonicalRecord::debtorIndex).distinct().count();
        List<TotalLine> totals = r.report().totals().stream().map(t -> new TotalLine(t.debtorIndex(), t.label(),
                t.column(), t.target(), t.document(), t.extracted(), t.matches())).toList();
        return new Summary(r.records().size(), toImport.size(), excluded, (int) debtors, errors, warnings,
                withError.size(), unrecognized, lowConfidence, decisions.confirmed().size(),
                sum(toImport, CanonicalField.PRINCIPAL_AMOUNT), sum(toImport, CanonicalField.REPORTED_BALANCE),
                r.totalsMatch(), r.report().blocking(), totals, codes);
    }

    boolean lowConfidence(CanonicalRecord record) {
        return record.origin() == RecordOrigin.VISUAL
                && (record.confidence() == null || record.confidence() < properties.minConfidence());
    }

    private static BigDecimal sum(List<CanonicalRecord> records, CanonicalField field) {
        BigDecimal total = BigDecimal.ZERO;
        for (CanonicalRecord r : records) {
            if (r.typed().contains(field)) {
                total = total.add(new BigDecimal(r.values().get(field)));
            }
        }
        return total;
    }

    String summaryJson(Result r, RecordDecisions decisions) {
        return json.toJson(summary(r, decisions));
    }

    Summary parseSummary(String summaryJson) {
        return summaryJson == null ? null : json.fromJson(summaryJson, Summary.class);
    }
}
