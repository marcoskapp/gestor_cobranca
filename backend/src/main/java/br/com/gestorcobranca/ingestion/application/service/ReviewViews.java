package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.application.ReviewProperties;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.AlertTypeView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.AlertView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.AnalysisView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.BlockerView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.ColumnFormatView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.CommitView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.ExampleView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.FormatOptionView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.FormatView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.ImpactView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.MeasureView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.ReviewSummaryView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.ReviewView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.TotalsCheckView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.TotalsStateView;
import br.com.gestorcobranca.ingestion.application.port.out.RowValueStore;
import br.com.gestorcobranca.ingestion.application.port.out.RowValueStore.Impact;
import br.com.gestorcobranca.ingestion.domain.model.ImportBatch;
import br.com.gestorcobranca.ingestion.domain.model.ImportBatchStatus;
import br.com.gestorcobranca.ingestion.domain.model.format.AmountReading;
import br.com.gestorcobranca.ingestion.domain.model.format.AmountStyle;
import br.com.gestorcobranca.ingestion.domain.model.format.ColumnFormatAnalysis;
import br.com.gestorcobranca.ingestion.domain.model.format.FileFormatAnalysis;
import br.com.gestorcobranca.ingestion.domain.model.format.FormatSample;
import br.com.gestorcobranca.ingestion.domain.model.format.NumberFormat;
import br.com.gestorcobranca.ingestion.domain.model.format.OptionSummary;
import br.com.gestorcobranca.ingestion.domain.model.review.AlertStatus;
import br.com.gestorcobranca.ingestion.domain.model.review.AlertType;
import br.com.gestorcobranca.ingestion.domain.model.review.AnalysisStatus;
import br.com.gestorcobranca.ingestion.domain.model.review.ImportReview;
import br.com.gestorcobranca.ingestion.domain.model.review.ImportReviewAlert;
import br.com.gestorcobranca.ingestion.domain.model.review.ImportTotalsCheck;
import br.com.gestorcobranca.ingestion.domain.model.review.ReviewBlocker;
import br.com.gestorcobranca.ingestion.domain.model.review.TotalsMeasure;
import br.com.gestorcobranca.ingestion.domain.repository.ImportReviewAlertRepository;
import br.com.gestorcobranca.ingestion.domain.repository.ImportTotalsCheckRepository;
import br.com.gestorcobranca.ingestion.domain.service.format.AmountParser;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Montagem das views da conferência (estado derivado da prévia corrente e das decisões gravadas). */
@Component
class ReviewViews {

    private static final int EXAMPLES = 8;

    private final ImportReviewAlertRepository alerts;
    private final ImportTotalsCheckRepository checks;
    private final RowValueStore rowValues;
    private final BatchCreation creation;
    private final ReviewProperties properties;
    private final CurrentActor currentActor;
    private final JsonCodec json;

    ReviewViews(ImportReviewAlertRepository alerts, ImportTotalsCheckRepository checks, RowValueStore rowValues,
                BatchCreation creation, ReviewProperties properties, CurrentActor currentActor, JsonCodec json) {
        this.alerts = alerts;
        this.checks = checks;
        this.rowValues = rowValues;
        this.creation = creation;
        this.properties = properties;
        this.currentActor = currentActor;
        this.json = json;
    }

    ReviewView review(ImportReview review, ImportBatch preview) {
        AnalysisView analysis = analysis(review);
        Impact impact = preview.getStatus() == ImportBatchStatus.VALIDATED
                ? rowValues.impact(preview.getId(), review.getCreditorId()) : null;
        List<BlockerView> blockers = review.getStatus() == br.com.gestorcobranca.ingestion.domain.model.review.ReviewStatus.OPEN
                ? blockers(review, preview, impact, analysis).stream()
                .map(b -> new BlockerView(b.name(), b.message())).toList() : List.of();
        BigDecimal threshold = properties.effectiveApprovalThreshold();
        ImpactView impactView = impact == null ? null : new ImpactView(impact.rows(), impact.rowsToImport(),
                impact.rowsWithError(), impact.rowsExcluded(), impact.newTitles(), impact.existingTitles(),
                impact.holdBySituation(), impact.closedBySituation(), impact.principalToImport(), impact.balanceToImport(),
                threshold != null && impact.principalToImport().compareTo(threshold) >= 0, threshold,
                creation.importedBefore(review.getPortfolioId(), preview.getFileSha256()) != null);
        CommitView commit = new CommitView(review.getCommitRequestedBy(), review.getCommitRequestedAt(),
                review.isApprovalRequired(), review.getApprovalThreshold(), review.getForceReason(), review.getDecidedBy(),
                review.getDecidedAt(), review.getDecisionNote(), review.getImportBatchId(), review.getCancelledAt(),
                review.getCancelledBy(), review.getCancelReason());
        return new ReviewView(review.getId(), review.getCreditorId(), review.getPortfolioId(), review.getStatus().name(),
                review.getCurrentStep(), review.getPreviews(), ImportViews.batch(preview, json), format(review, preview),
                totals(review), analysis, impactView, blockers, properties.confirmationText(), commit,
                ImportReviewService.canApprove(currentActor.find().orElse(null), review), review.getCreatedBy(),
                review.getCreatedAt());
    }

    ReviewSummaryView summary(ImportReview r, ImportBatch preview) {
        BigDecimal amount = null;
        if (r.getImpact() != null) {
            Impact impact = json.fromJson(r.getImpact(), Impact.class);
            amount = impact.principalToImport();
        }
        return new ReviewSummaryView(r.getId(), r.getCreditorId(), r.getPortfolioId(), r.getStatus().name(),
                r.getCurrentStep(), preview == null ? null : preview.getFileName(), r.getCurrentBatchId(),
                preview == null ? null : preview.getStatus().name(), r.getImportBatchId(), r.isApprovalRequired(), amount,
                r.getCreatedBy(), r.getCreatedAt(), r.getCommitRequestedBy(), r.getCommitRequestedAt());
    }

    List<ReviewBlocker> blockers(ImportReview review, ImportBatch preview) {
        Impact impact = preview.getStatus() == ImportBatchStatus.VALIDATED
                ? rowValues.impact(preview.getId(), review.getCreditorId()) : null;
        return blockers(review, preview, impact, analysis(review));
    }

    private List<ReviewBlocker> blockers(ImportReview review, ImportBatch preview, Impact impact, AnalysisView analysis) {
        long rowsToImport = impact == null ? preview.getRowsValid() : impact.rowsToImport();
        return review.blockers(new ImportReview.PreviewState(preview.getStatus(), rowsToImport),
                new ImportReview.OpenAlerts(analysis.open(), analysis.openScale()), properties.limits());
    }

    // ------------------------------------------------------------ formato

    private FormatView format(ImportReview review, ImportBatch preview) {
        FileFormatAnalysis analysis = NumberFormats.analysisFromJson(json, preview.getFormatAnalysis());
        if (analysis == null) {
            return new FormatView(false, false, false, null, null, 0, 0, 0, List.of());
        }
        NumberFormat applied = NumberFormats.fromJson(json, preview.getNumberFormat());
        List<ColumnFormatView> columns = analysis.columns().stream().map(c -> column(c, analysis, applied)).toList();
        long typed = analysis.columns().stream().mapToLong(ColumnFormatAnalysis::typedCount).sum();
        return new FormatView(true, preview.isAwaitingFormat(), review.isFormatConfirmed(),
                review.isFormatConfirmed() ? review.getFormatConfirmedAt() : null,
                review.isFormatConfirmed() ? review.getFormatConfirmedBy() : null, analysis.rows(), typed,
                analysis.textValues(), columns);
    }

    private static ColumnFormatView column(ColumnFormatAnalysis c, FileFormatAnalysis analysis, NumberFormat applied) {
        var decision = applied == null ? java.util.Optional.<br.com.gestorcobranca.ingestion.domain.model.format.ColumnDecision>empty()
                : applied.decision(c.column());
        List<FormatSample> samples = c.samples().stream().limit(EXAMPLES).toList();
        List<FormatOptionView> options = c.options().stream().map(o -> option(o, samples)).toList();
        Map<String, Long> styles = new LinkedHashMap<>();
        c.styleCounts().forEach((style, n) -> styles.put(style.name(), n));
        if (c.typedCount() > 0) {
            styles.put(AmountStyle.TYPED.name(), c.typedCount());
        }
        return new ColumnFormatView(c.column(), c.status().name(), c.requiresDecision(), c.typedCount(), c.textCount(),
                styles, decision.map(d -> d.option().name()).orElse(null),
                decision.map(d -> d.source().name()).orElse(null),
                c.recommended() == null ? null : c.recommended().name(),
                c.requiresDecision() ? analysis.suggestion(c.column()).map(Enum::name).orElse(null) : null, options);
    }

    private static FormatOptionView option(OptionSummary o, List<FormatSample> samples) {
        List<ExampleView> examples = samples.stream().map(s -> {
            AmountReading reading = s.style() == AmountStyle.TYPED ? AmountParser.readTyped(s.raw())
                    : AmountParser.read(s.raw(), o.option());
            return new ExampleView(s.rowNumber(), s.raw(), s.style().name(), reading.value(),
                    reading.problem() == null ? null : reading.problem().name());
        }).toList();
        return new FormatOptionView(o.option().name(), o.total(), o.invalidCount(), examples);
    }

    // ------------------------------------------------------------ totais

    private TotalsStateView totals(ImportReview review) {
        TotalsCheckView last = review.isTotalsCheckedOnCurrent()
                ? checks.findFirstByBatchIdOrderByCreatedAtDesc(review.getCurrentBatchId()).map(this::totalsCheck).orElse(null)
                : null;
        return new TotalsStateView(review.isTotalsCheckedOnCurrent(), review.isTotalsMatched(), review.getTotalsChecks(),
                review.getTotalsMismatches(), last);
    }

    TotalsCheckView totalsCheck(ImportTotalsCheck check) {
        ImportReviewService.TotalsHints hints = json.fromJson(check.getHints(), ImportReviewService.TotalsHints.class);
        List<MeasureView> measures = hints.measures().stream().map(m -> new MeasureView(m.measure().name(),
                m.measure().field() == null ? null : m.measure().field().column(), m.entered(), m.computed(),
                m.difference(), m.matches(), m.scaleExponent(),
                m.measure() == TotalsMeasure.ROWS ? 0 : hints.invalid().getOrDefault(m.measure(), 0L))).toList();
        return new TotalsCheckView(check.getId(), check.getBatchId(), check.isMatched(), measures, hints.hints(),
                hints.suspects(), hints.equal(), check.getCreatedBy(), check.getCreatedAt());
    }

    // ------------------------------------------------------------ alertas

    AnalysisView analysis(ImportReview review) {
        Map<AlertType, long[]> byType = new EnumMap<>(AlertType.class);
        if (review.getAnalysisStatus() == AnalysisStatus.DONE) {
            for (var count : alerts.countByType(review.getCurrentBatchId())) {
                long[] counts = byType.computeIfAbsent(count.type(), t -> new long[3]);
                counts[count.status().ordinal()] += count.count();
            }
        }
        long open = 0;
        long openScale = 0;
        long confirmed = 0;
        long excluded = 0;
        List<AlertTypeView> types = new java.util.ArrayList<>();
        for (var e : byType.entrySet()) {
            long[] c = e.getValue();
            open += c[AlertStatus.OPEN.ordinal()];
            confirmed += c[AlertStatus.CONFIRMED.ordinal()];
            excluded += c[AlertStatus.EXCLUDED.ordinal()];
            if (e.getKey().scale()) {
                openScale += c[AlertStatus.OPEN.ordinal()];
            }
            types.add(new AlertTypeView(e.getKey().name(), e.getKey().scale(), c[AlertStatus.OPEN.ordinal()],
                    c[AlertStatus.CONFIRMED.ordinal()], c[AlertStatus.EXCLUDED.ordinal()]));
        }
        long rowsExcluded = review.getAnalysisStatus() == AnalysisStatus.DONE && excluded > 0
                ? alerts.excludedRows(review.getCurrentBatchId()).size() : 0;
        Map<String, Object> references = review.getAnalysisSummary() == null ? Map.of() : json.toMap(review.getAnalysisSummary());
        return new AnalysisView(review.getAnalysisStatus().name(), review.getAlertsTotal(), review.isAlertsTruncated(),
                open, openScale, confirmed, excluded, rowsExcluded, types, references);
    }

    static AlertView alert(ImportReviewAlert a) {
        BigDecimal suggested = a.getSuggestedExponent() == null || a.getAmount() == null ? null
                : a.getAmount().scaleByPowerOfTen(a.getSuggestedExponent()).setScale(2, java.math.RoundingMode.HALF_EVEN);
        return new AlertView(a.getId(), a.getRowNumber(), a.getExternalId(), a.getType().name(), a.getType().scale(),
                a.getPriority(), a.getField(), a.getAmount(), a.getExpectedLow(), a.getExpectedHigh(),
                a.getReference() == null ? null : a.getReference().name(), a.getReferenceSize(), a.getSuggestedExponent(),
                suggested, a.getDetail(), a.getStatus().name(), a.getDecidedBy(), a.getDecidedAt(), a.getJustification());
    }
}
