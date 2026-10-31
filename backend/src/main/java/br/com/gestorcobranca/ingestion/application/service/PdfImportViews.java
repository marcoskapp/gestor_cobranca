package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.CanonicalFieldOption;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.FindingView;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.MapProblemView;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.PageView;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.PdfImportSummaryView;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.PdfImportView;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.PdfRecordView;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.PdfTemplateView;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.RegionView;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.RevisionView;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.SummaryView;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.TotalsView;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.UsageView;
import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.pdf.CanonicalRecord;
import br.com.gestorcobranca.ingestion.domain.model.pdf.CellValue;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ControlValue;
import br.com.gestorcobranca.ingestion.domain.model.pdf.DebtorBlock;
import br.com.gestorcobranca.ingestion.domain.model.pdf.Finding;
import br.com.gestorcobranca.ingestion.domain.model.pdf.LineNote;
import br.com.gestorcobranca.ingestion.domain.model.pdf.MapOrigin;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfImport;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfImportStatus;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfLayoutTemplate;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfMapRevision;
import br.com.gestorcobranca.ingestion.domain.model.pdf.RecordDecisions;
import br.com.gestorcobranca.ingestion.domain.model.pdf.SourceRef;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TextPage;
import br.com.gestorcobranca.ingestion.domain.model.pdf.TotalsCheck;
import br.com.gestorcobranca.ingestion.domain.service.IssueValueMasker;
import br.com.gestorcobranca.ingestion.domain.service.pdf.MapExplainer;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Conversão das importações PDF em visões (sem entidades e sem valor do devedor fora dos títulos). */
@Component
class PdfImportViews {

    static final int MAX_FINDINGS = 300;
    private static final String MASK = "•••";
    private static final List<CanonicalFieldOption> FIELDS = Arrays.stream(CanonicalField.values())
            .map(f -> new CanonicalFieldOption(f.column(), f.required(), f.personal())).toList();

    private final JsonCodec json;

    PdfImportViews(JsonCodec json) {
        this.json = json;
    }

    PdfImportSummaryView summary(PdfImport p, PdfAnalysis.Summary s) {
        return new PdfImportSummaryView(p.getId(), p.getCreditorId(), p.getPortfolioId(), p.getChannel().name(),
                p.getStatus().name(), p.getFileName(), p.getPageCount(), p.hasTextLayer(),
                p.getMapOrigin() == null ? null : p.getMapOrigin().name(), s == null ? 0 : s.records(),
                s != null && s.totalsMatch(), p.getReviewId(), p.getCreatedBy(), p.getCreatedAt());
    }

    /**
     * @param result análise com o mapa corrente (nula quando não há o que analisar: sem mapa, arquivo descartado,
     *               encerrada)
     */
    PdfImportView full(PdfImport p, PdfAnalysis.Result result, RecordDecisions decisions, PdfAnalysis.Summary summary,
                       List<PdfMapRevision> revisions, PdfLayoutTemplate template, List<String> blockers) {
        List<FindingView> findings = new ArrayList<>();
        List<TotalsView> totals = new ArrayList<>();
        List<RegionView> regions = new ArrayList<>();
        List<PageView> pages = new ArrayList<>();
        List<MapProblemView> problems = new ArrayList<>();
        if (result != null) {
            result.report().findings().stream().filter(f -> f.record() == null).limit(MAX_FINDINGS)
                    .forEach(f -> findings.add(finding(f)));
            for (TotalsCheck t : result.report().totals()) {
                totals.add(new TotalsView(t.debtorIndex() < 0 ? "DOCUMENT" : String.valueOf(t.debtorIndex() + 1),
                        t.debtorIndex(), t.label(), t.column(), t.target(), t.document(), t.extracted(), t.difference(),
                        t.matches(), t.records(), region("TOTAL", t.source())));
            }
            result.mapProblems().forEach(m -> problems.add(new MapProblemView(m.code(), m.path(), m.message())));
            if (result.text() != null) {
                for (TextPage page : result.text().pages()) {
                    pages.add(new PageView(page.number(), page.width(), page.height()));
                }
            }
            if (result.extraction() != null) {
                result.extraction().headerRegions().forEach(r -> regions.add(region("HEADER", r)));
                for (DebtorBlock b : result.extraction().debtors()) {
                    if (b.region() != null) {
                        regions.add(region("DEBTOR", b.region()));
                    }
                    for (CellValue v : b.fields().values()) {
                        regions.add(region("DEBTOR", v.source()));
                    }
                }
                for (ControlValue c : result.extraction().controls()) {
                    regions.add(region("TOTAL", c.value().source()));
                }
                for (CellValue v : result.extraction().documentFields().values()) {
                    regions.add(region("DOCUMENT_FIELD", v.source()));
                }
                for (LineNote n : result.extraction().unrecognized()) {
                    regions.add(region("UNRECOGNIZED", n.source()));
                }
            }
        }
        regions.removeIf(java.util.Objects::isNull);
        boolean visual = p.getMapOrigin() == MapOrigin.VISUAL;
        boolean justificationRequired = result != null && !result.totalsMatch();
        return new PdfImportView(p.getId(), p.getCreditorId(), p.getPortfolioId(), p.getChannel().name(),
                p.getStatus().name(), p.getFileName(), p.getFileSize(), p.getFileSha256(), p.getPageCount(),
                p.hasTextLayer(), p.getFileKey() != null, p.getMapOrigin() == null ? null : p.getMapOrigin().name(),
                p.getMapRevision(), p.getTemplateId(), template == null ? null : template.getName(),
                p.getMap() == null ? null : json.toMap(p.getMap()), problems,
                result == null || result.map() == null ? List.of() : MapExplainer.explain(result.map()),
                summary == null ? null : summaryView(summary), totals, findings, pages, regions,
                new UsageView(p.getAiCalls(), p.getAiInputTokens(), p.getAiOutputTokens(), p.getAiCost(),
                        p.getAiCurrency(), p.getAiModel(), p.getAiLatencyMs()),
                p.getFailureCode(), p.getFailureMessage(), blockers,
                justificationRequired && p.getStatus() == PdfImportStatus.AWAITING_VALIDATION,
                visual && p.getStatus() == PdfImportStatus.AWAITING_VALIDATION, p.getReviewId(), p.getApprovedBy(),
                p.getApprovedAt(), p.getTotalsJustification(), p.getDiscardedBy(), p.getDiscardedAt(),
                p.getDiscardReason(), revisions.stream().map(r -> new RevisionView(r.getRevision(), r.getOrigin().name(),
                        r.getRecords(), r.isTotalsMatch(), r.isBlocking(), r.getCreatedBy(), r.getCreatedAt())).toList(),
                FIELDS, p.getCreatedBy(), p.getCreatedAt());
    }

    static SummaryView summaryView(PdfAnalysis.Summary s) {
        return new SummaryView(s.records(), s.recordsToImport(), s.excluded(), s.debtors(), s.errors(), s.warnings(),
                s.recordsWithError(), s.unrecognizedLines(), s.lowConfidence(), s.confirmed(),
                s.principalTotal() == null ? BigDecimal.ZERO : s.principalTotal(),
                s.balanceTotal() == null ? BigDecimal.ZERO : s.balanceTotal(), s.totalsMatch(), s.blocking());
    }

    PdfRecordView record(CanonicalRecord r, List<Finding> findings, RecordDecisions decisions, boolean masked,
                         boolean lowConfidence) {
        Map<String, String> values = new LinkedHashMap<>();
        r.values().forEach((field, value) -> values.put(field.column(), masked && field.personal()
                ? IssueValueMasker.excerpt(field, value) : value));
        Map<String, String> info = new LinkedHashMap<>();
        r.info().forEach((k, v) -> info.put(k, masked ? MASK : v));
        Map<String, List<RegionView>> sources = new LinkedHashMap<>();
        r.sources().forEach((k, refs) -> sources.put(k, refs.stream().map(ref -> region("VALUE", ref)).toList()));
        String status = findings.stream().anyMatch(f -> f.severity() == Finding.Severity.ERROR) ? "ERROR"
                : findings.stream().anyMatch(f -> f.severity() == Finding.Severity.WARNING) || lowConfidence ? "WARNING"
                : "OK";
        String decision = decisions.isExcluded(r.ordinal()) ? "EXCLUDED" : decisions.isConfirmed(r.ordinal()) ? "CONFIRMED" : null;
        return new PdfRecordView(r.ordinal(), r.debtorIndex(), r.origin().name(), r.confidence(), values, info, sources,
                r.lines().stream().map(l -> region("LINE", l)).toList(), r.page(), status,
                findings.stream().map(PdfImportViews::finding).toList(), decision, masked, lowConfidence);
    }

    PdfTemplateView template(PdfLayoutTemplate t) {
        return new PdfTemplateView(t.getId(), t.getCreditorId(), t.getName(), t.getFingerprint(), t.getTemplateVersion(),
                t.isActive(), t.getUses(), t.getLastUsedAt(), t.getSourceImportId(), json.toMap(t.getMap()),
                t.getCreatedBy(), t.getCreatedAt());
    }

    static FindingView finding(Finding f) {
        return new FindingView(f.severity().name(), f.code(), f.message(), f.record(), f.field(), f.blocking(),
                region("VALUE", f.source()));
    }

    static RegionView region(String kind, SourceRef ref) {
        return ref == null ? null : new RegionView(kind, ref.page(), ref.x0(), ref.top(), ref.x1(), ref.bottom());
    }
}
