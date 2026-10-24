package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.application.ReviewProperties;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases;
import br.com.gestorcobranca.ingestion.application.port.out.RowValueStore;
import br.com.gestorcobranca.ingestion.application.port.out.RowValueStore.Impact;
import br.com.gestorcobranca.ingestion.domain.model.FileLayout;
import br.com.gestorcobranca.ingestion.domain.model.ImportBatch;
import br.com.gestorcobranca.ingestion.domain.model.ImportBatchStatus;
import br.com.gestorcobranca.ingestion.domain.model.ImportFormat;
import br.com.gestorcobranca.ingestion.domain.model.ImportMode;
import br.com.gestorcobranca.ingestion.domain.model.ImportSource;
import br.com.gestorcobranca.ingestion.domain.model.format.ColumnDecision;
import br.com.gestorcobranca.ingestion.domain.model.format.ColumnFormatAnalysis;
import br.com.gestorcobranca.ingestion.domain.model.format.DecisionSource;
import br.com.gestorcobranca.ingestion.domain.model.format.FileFormatAnalysis;
import br.com.gestorcobranca.ingestion.domain.model.format.FormatOption;
import br.com.gestorcobranca.ingestion.domain.model.format.NumberFormat;
import br.com.gestorcobranca.ingestion.domain.model.review.AlertDecision;
import br.com.gestorcobranca.ingestion.domain.model.review.AlertStatus;
import br.com.gestorcobranca.ingestion.domain.model.review.AlertType;
import br.com.gestorcobranca.ingestion.domain.model.review.AnalysisStatus;
import br.com.gestorcobranca.ingestion.domain.model.review.ComputedTotals;
import br.com.gestorcobranca.ingestion.domain.model.review.ControlTotalsInput;
import br.com.gestorcobranca.ingestion.domain.model.review.ImportReview;
import br.com.gestorcobranca.ingestion.domain.model.review.ImportReviewAlert;
import br.com.gestorcobranca.ingestion.domain.model.review.ImportTotalsCheck;
import br.com.gestorcobranca.ingestion.domain.model.review.MeasureComparison;
import br.com.gestorcobranca.ingestion.domain.model.review.ReviewBlocker;
import br.com.gestorcobranca.ingestion.domain.model.review.ReviewStatus;
import br.com.gestorcobranca.ingestion.domain.model.review.TotalsComparison;
import br.com.gestorcobranca.ingestion.domain.model.review.TotalsMeasure;
import br.com.gestorcobranca.ingestion.domain.repository.ImportBatchRepository;
import br.com.gestorcobranca.ingestion.domain.repository.ImportReviewAlertRepository;
import br.com.gestorcobranca.ingestion.domain.repository.ImportReviewRepository;
import br.com.gestorcobranca.ingestion.domain.repository.ImportTotalsCheckRepository;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.PortfolioView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.storage.FileStorage.StoredFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Casos de uso da conferência da importação ({@code docs/tecnico/desenho/importacao.md}). */
@Service
@Transactional
class ImportReviewService implements ImportReviewUseCases {

    static final String RESOURCE = "import_review";
    private static final Logger LOG = LoggerFactory.getLogger(ImportReviewService.class);
    private static final int SUSPECT_ROWS = 10;

    private final ImportReviewRepository reviews;
    private final ImportBatchRepository batches;
    private final ImportReviewAlertRepository alerts;
    private final ImportTotalsCheckRepository checks;
    private final RowValueStore rowValues;
    private final BatchCreation creation;
    private final JobQueue jobQueue;
    private final ReviewViews views;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final IngestionMetrics metrics;
    private final ReviewProperties properties;
    private final JsonCodec json;
    private final Clock clock;

    ImportReviewService(ImportReviewRepository reviews, ImportBatchRepository batches, ImportReviewAlertRepository alerts,
                        ImportTotalsCheckRepository checks, RowValueStore rowValues, BatchCreation creation,
                        JobQueue jobQueue, ReviewViews views, AccessScope accessScope, CurrentActor currentActor,
                        AuditTrail auditTrail, BusinessEventLogger businessEvents, IngestionMetrics metrics,
                        ReviewProperties properties, JsonCodec json, Clock clock) {
        this.reviews = reviews;
        this.batches = batches;
        this.alerts = alerts;
        this.checks = checks;
        this.rowValues = rowValues;
        this.creation = creation;
        this.jobQueue = jobQueue;
        this.views = views;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- início

    @Override
    public ReviewView start(StartReviewCommand command) {
        PortfolioView portfolio = creation.requirePortfolio(command.portfolioId());
        String fileName = creation.fileName(command.fileName());
        ImportFormat format = creation.formatOf(fileName);
        FileLayout layout = creation.resolveLayout(command.layoutId(), portfolio, format);
        StoredFile stored = creation.storeUpload(command.content(), fileName, command.contentType(), command.size(), format);
        return open(portfolio, layout, ImportSource.of(format), stored, null);
    }

    @Override
    public ReviewView startFromBatch(UUID batchId) {
        ImportBatch original = batches.findById(batchId)
                .orElseThrow(() -> ResourceNotFoundException.of(BatchCreation.RESOURCE, batchId));
        accessScope.requireVisible(BatchCreation.RESOURCE, batchId, original.getCreditorId());
        Guard.requireState(original.getStatus().isTerminal(), "import_batch.not_finished",
                "Somente lotes encerrados podem ser conferidos novamente");
        PortfolioView portfolio = creation.requirePortfolio(original.getPortfolioId());
        return open(portfolio, creation.layoutOf(original), original.getSource(), BatchCreation.storedOf(original),
                original.getId());
    }

    private ReviewView open(PortfolioView portfolio, FileLayout layout, ImportSource source, StoredFile stored,
                            UUID originBatchId) {
        ImportReview review = new ImportReview(portfolio.creditorId(), portfolio.id());
        ImportBatch preview = creation.create(portfolio, layout, source, ImportMode.VALIDATE_ONLY, stored, null,
                originBatchId, new ImportBatch.Options(review.getId(), null, null));
        review.attachPreview(preview.getId());
        reviews.save(review);
        auditTrail.record(AuditEntry.of("import_review.created", RESOURCE, review.getId())
                .creditor(review.getCreditorId())
                .created("portfolioId", review.getPortfolioId())
                .created("previewBatchId", preview.getId())
                .created("fileSha256", preview.getFileSha256())
                .meta("originBatchId", originBatchId));
        LOG.atInfo().addKeyValue("reviewId", review.getId()).addKeyValue("batchId", preview.getId())
                .log("Conferência de importação iniciada");
        return views.review(review, preview);
    }

    // ---------------------------------------------------------------- consultas

    @Override
    @Transactional(readOnly = true)
    public ReviewView get(UUID reviewId) {
        ImportReview review = loadVisible(reviewId);
        return views.review(review, preview(review));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ReviewSummaryView> search(ReviewSearch filter, PageQuery page) {
        ReviewSearch f = filter == null ? new ReviewSearch(null, null, null) : filter;
        if (f.creditorId() != null && !accessScope.canAccessCreditor(f.creditorId())) {
            return new PageResponse<>(List.of(), page.page(), page.size(), 0, 0);
        }
        List<UUID> allowed = accessScope.hasAllCreditors() ? null : List.copyOf(accessScope.restrictCreditors(null));
        List<ReviewStatus> statuses = f.status() == null || f.status().isBlank() ? List.of()
                : Arrays.stream(f.status().split(",")).map(String::strip).filter(v -> !v.isEmpty())
                .map(v -> parseEnum(ReviewStatus.class, v, "import_review.status_invalid")).toList();
        var result = reviews.search(allowed, new ImportReviewRepository.Criteria(f.creditorId(), f.portfolioId(), statuses),
                page.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<UUID, ImportBatch> previews = new LinkedHashMap<>();
        for (ImportReview r : result.getContent()) {
            batches.findById(r.getCurrentBatchId()).ifPresent(b -> previews.put(r.getId(), b));
        }
        return PageResponse.of(result, r -> views.summary(r, previews.get(r.getId())));
    }

    // ---------------------------------------------------------------- passo atual

    @Override
    public ReviewView moveTo(UUID reviewId, int step) {
        ImportReview review = loadForCommand(reviewId);
        review.moveTo(step);
        reviews.save(review);
        return views.review(review, preview(review));
    }

    // ---------------------------------------------------------------- passo 2: formato

    @Override
    public ReviewView confirmFormat(UUID reviewId, FormatCommand command) {
        ImportReview review = loadForCommand(reviewId);
        review.requireOpen();
        ImportBatch preview = preview(review);
        if (command != null && command.batchId() != null && !command.batchId().equals(preview.getId())) {
            throw new ConflictException("import_review.preview_outdated", "A prévia mudou: atualize a tela");
        }
        FileFormatAnalysis analysis = NumberFormats.analysisFromJson(json, preview.getFormatAnalysis());
        if (analysis == null) {
            throw new ConflictException("import_review.format_not_ready",
                    "A leitura do arquivo ainda não terminou; aguarde alguns instantes");
        }
        NumberFormat applied = Objects.requireNonNullElse(NumberFormats.fromJson(json, preview.getNumberFormat()),
                NumberFormat.empty());
        NumberFormat chosen = merge(analysis, applied, command == null ? null : command.decisions());
        NumberFormat.Resolution resolution = NumberFormat.resolve(analysis, chosen);
        if (!resolution.conflicting().isEmpty()) {
            throw new BusinessRuleException("import_review.format_option_not_allowed",
                    "Interpretação não disponível para: " + String.join(", ", resolution.conflicting()));
        }
        if (!resolution.undecided().isEmpty()) {
            throw new BusinessRuleException("import_review.format_decision_required",
                    "Escolha como ler os valores de: " + String.join(", ", resolution.undecided()));
        }
        NumberFormat decided = resolution.applied();
        Instant now = Instant.now(clock);
        UUID actor = currentActor.actorId();
        ImportBatch target = preview;
        boolean newPreview = false;
        if (preview.isAwaitingFormat()) {
            preview.applyFormat(json.toJson(decided));
            batches.save(preview);
            jobQueue.enqueue(ImportBatchJobHandler.TYPE, new ImportBatchJobHandler.Payload(preview.getId(), 1), null,
                    ImportBatchJobHandler.deduplicationKey(preview.getId(), 1));
        } else if (!sameOptions(applied, decided)) {
            // a decisão muda valores já validados: nova prévia do mesmo arquivo (as ocorrências são imutáveis)
            if (preview.isInProgress() && !preview.isCancelRequested()) {
                preview.requestCancel(now, actor, "Formato numérico alterado na conferência");
                batches.save(preview);
            }
            PortfolioView portfolio = creation.requirePortfolio(review.getPortfolioId());
            target = creation.create(portfolio, creation.layoutOf(preview), preview.getSource(), ImportMode.VALIDATE_ONLY,
                    BatchCreation.storedOf(preview), null, preview.getId(),
                    new ImportBatch.Options(review.getId(), json.toJson(decided), null));
            review.attachPreview(target.getId());
            newPreview = true;
        }
        review.confirmFormat(target.getId(), actor, now);
        reviews.save(review);
        Map<String, String> before = NumberFormats.summary(applied);
        Map<String, String> after = NumberFormats.summary(decided);
        auditTrail.record(AuditEntry.of("import_review.format_confirmed", RESOURCE, review.getId())
                .creditor(review.getCreditorId())
                .change("numberFormat", before, after)
                .meta("batchId", target.getId())
                .meta("newPreview", newPreview)
                .meta("columns", analysis.columns().stream()
                        .collect(Collectors.toMap(ColumnFormatAnalysis::column, c -> c.status().name()))));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.IMPORT_FORMAT_CONFIRMED)
                .creditor(review.getCreditorId()).portfolio(review.getPortfolioId())
                .reference(RESOURCE, review.getId())
                .attr("batchId", target.getId())
                .attr("decisions", after)
                .attr("statuses", analysis.columns().stream()
                        .collect(Collectors.toMap(ColumnFormatAnalysis::column, c -> c.status().name())))
                .attr("decisionRequired", analysis.requiresDecision())
                .attr("textValues", analysis.textValues())
                .attr("newPreview", newPreview)
                .attr("requestedBy", actor));
        return views.review(review, target);
    }

    /** Decisões do usuário sobre as já aplicadas; colunas omitidas mantêm a aplicada. */
    private static NumberFormat merge(FileFormatAnalysis analysis, NumberFormat applied, Map<String, String> decisions) {
        NumberFormat user = NumberFormats.fromColumns(decisions, DecisionSource.USER);
        NumberFormat merged = NumberFormat.empty();
        for (ColumnFormatAnalysis column : analysis.columns()) {
            var given = user == null ? java.util.Optional.<ColumnDecision>empty() : user.decision(column.column());
            if (given.isPresent()) {
                merged = merged.with(column.column(), given.get());
            } else {
                var current = applied.decision(column.column());
                if (current.isPresent()) {
                    merged = merged.with(column.column(), current.get());
                }
            }
        }
        return merged;
    }

    private static boolean sameOptions(NumberFormat a, NumberFormat b) {
        return NumberFormats.summary(a).equals(NumberFormats.summary(b));
    }

    // ---------------------------------------------------------------- passo 4: totais

    @Override
    public TotalsCheckView checkTotals(UUID reviewId, TotalsCommand command) {
        ImportReview review = loadForCommand(reviewId);
        review.requireOpen();
        ImportBatch preview = preview(review);
        requireValidated(review, preview);
        Guard.require(command != null, "import_review.totals_required", "Informe ao menos um total da sua planilha");
        ControlTotalsInput input = new ControlTotalsInput(command.rows(), command.principalTotal(), command.balanceTotal(),
                command.contractAmountTotal());
        ComputedTotals computed = rowValues.totals(preview.getId());
        TotalsComparison comparison = properties.controlTotals().compare(input, computed);
        FileFormatAnalysis analysis = NumberFormats.analysisFromJson(json, preview.getFormatAnalysis());
        NumberFormat applied = Objects.requireNonNullElse(NumberFormats.fromJson(json, preview.getNumberFormat()),
                NumberFormat.empty());
        TotalsHints hints = hints(preview, comparison, computed, analysis, applied);
        ImportTotalsCheck check = checks.save(new ImportTotalsCheck(review.getId(), preview.getId(), input, computed,
                comparison, json.toJson(hints)));
        review.totalsChecked(preview.getId(), comparison.matches(), Instant.now(clock));
        reviews.save(review);
        boolean alternative = hints.hints().stream().anyMatch(h -> "ALTERNATIVE_MATCH".equals(h.code()));
        Integer exponent = comparison.scaleHint().map(MeasureComparison::scaleExponent).orElse(null);
        auditTrail.record(AuditEntry.of("import_review.totals_checked", RESOURCE, review.getId())
                .creditor(review.getCreditorId())
                .created("entered", input.entered())
                .created("computed", comparison.measures().stream()
                        .collect(Collectors.toMap(m -> m.measure().name(), MeasureComparison::computed)))
                .created("matched", comparison.matches())
                .meta("batchId", preview.getId())
                .meta("checkId", check.getId()));
        businessEvents.log(BusinessEvent.of(comparison.matches() ? BusinessEventTypes.IMPORT_TOTALS_MATCHED
                        : BusinessEventTypes.IMPORT_TOTALS_MISMATCH)
                .creditor(review.getCreditorId()).portfolio(review.getPortfolioId())
                .reference(RESOURCE, review.getId())
                .amount(computed.value(TotalsMeasure.PRINCIPAL))
                .attr("origin", "REVIEW")
                .attr("batchId", preview.getId())
                .attr("measures", input.entered().keySet().stream().map(Enum::name).toList())
                .attr("mismatched", comparison.mismatched().stream().map(m -> m.measure().name()).toList())
                .attr("scaleExponent", exponent)
                .attr("alternativeMatch", alternative)
                .attr("attempt", review.getTotalsChecks())
                .attr("requestedBy", currentActor.actorId()));
        metrics.totalsCheck("review", comparison.matches(),
                comparison.matches() ? "none" : exponent != null ? "scale" : alternative ? "alternative" : "none");
        return views.totalsCheck(check);
    }

    /** Dicas para localizar uma divergência ({@code importacao.md} §3). */
    private TotalsHints hints(ImportBatch preview, TotalsComparison comparison, ComputedTotals computed,
                              FileFormatAnalysis analysis, NumberFormat applied) {
        List<HintView> hints = new ArrayList<>();
        List<SuspectRowView> suspects = List.of();
        List<SuspectRowView> equal = List.of();
        Map<TotalsMeasure, Long> invalid = new EnumMap<>(TotalsMeasure.class);
        for (TotalsMeasure m : List.of(TotalsMeasure.PRINCIPAL, TotalsMeasure.BALANCE, TotalsMeasure.CONTRACT_AMOUNT)) {
            invalid.put(m, computed.invalid(m));
        }
        if (comparison.matches()) {
            hints.add(new HintView("MATCHED", null, "Os totais conferem com a sua planilha."));
            return new TotalsHints(comparison.measures(), invalid, hints, suspects, equal);
        }
        boolean suspectsLoaded = false;
        for (MeasureComparison m : comparison.mismatched()) {
            if (m.measure() == TotalsMeasure.ROWS) {
                hints.add(new HintView("ROWS", m.measure().name(), ("O arquivo tem %s linhas de dados e a sua contagem tem %s. "
                        + "O cabeçalho não conta e linhas totalmente vazias são ignoradas.")
                        .formatted(m.computed().toPlainString(), m.entered().toPlainString())));
                continue;
            }
            String column = m.measure().field().column();
            if (m.scaleExponent() != null) {
                // n = informado ÷ calculado: n < 0, o arquivo ficou maior que a planilha; n > 0, menor
                int n = m.scaleExponent();
                String relation = n < 0 ? "%s vezes o total".formatted(pow(n)) : "1/%s do total".formatted(pow(n));
                String example = n < 0 ? "1.00 (um real) lido como 100" : "1.000 (mil) lido como 1,00";
                hints.add(new HintView("SCALE", m.measure().name(), ("O arquivo soma %s da sua planilha em %s: parece erro "
                        + "de separador decimal (por exemplo, %s). Confira as linhas indicadas e o formato no passo 2.")
                        .formatted(relation, column, example)));
            }
            if (analysis != null) {
                analysis.column(column).ifPresent(c -> c.options().stream()
                        .filter(o -> o.option() != applied.option(m.measure().field()))
                        .filter(o -> o.total().subtract(m.entered()).abs().compareTo(properties.amountTolerance()) <= 0)
                        .findFirst()
                        .ifPresent(o -> hints.add(new HintView("ALTERNATIVE_MATCH", m.measure().name(),
                                "A sua soma bate com outra leitura da coluna %s (%s). Volte ao passo 2 e escolha essa interpretação."
                                        .formatted(column, optionLabel(o.option()))))));
            }
            long bad = computed.invalid(m.measure());
            if (bad > 0) {
                hints.add(new HintView("INVALID_VALUES", m.measure().name(),
                        "%d valor(es) de %s não puderam ser lidos e ficaram fora da soma (veja as ocorrências do passo 3)."
                                .formatted(bad, column)));
            }
            if (!suspectsLoaded) {
                RowValueStore.Suspects found = rowValues.suspects(preview.getId(), m.measure(), m.difference(), SUSPECT_ROWS);
                suspects = found.largestOrAmbiguous().stream().map(ImportReviewService::suspect).toList();
                equal = found.equalToDifference().stream().map(ImportReviewService::suspect).toList();
                suspectsLoaded = true;
                if (!equal.isEmpty()) {
                    hints.add(new HintView("EQUAL_ROW", m.measure().name(),
                            "A diferença (%s) é igual ao valor da(s) linha(s) %s: confira se ela está duplicada ou fora da sua soma."
                                    .formatted(m.difference().abs().toPlainString(),
                                            equal.stream().map(r -> String.valueOf(r.rowNumber())).collect(Collectors.joining(", ")))));
                }
            }
        }
        return new TotalsHints(comparison.measures(), invalid, hints, suspects, equal);
    }

    private static SuspectRowView suspect(RowValueStore.SuspectRow r) {
        return new SuspectRowView(r.rowNumber(), r.externalId(), r.amount(), r.ambiguous());
    }

    static String optionLabel(FormatOption option) {
        return switch (option) {
            case COMMA -> "vírgula decimal";
            case DOT -> "ponto decimal";
            case PER_VALUE_GROUPING -> "cada valor pelo próprio formato, ambíguos como milhar";
            case PER_VALUE_DECIMAL -> "cada valor pelo próprio formato, ambíguos como decimal";
        };
    }

    private static String pow(int n) {
        return "1" + "0".repeat(Math.abs(n));
    }

    /** Conteúdo de {@code import_totals_check.hints}. */
    record TotalsHints(List<MeasureComparison> measures, Map<TotalsMeasure, Long> invalid, List<HintView> hints,
                       List<SuspectRowView> suspects, List<SuspectRowView> equal) {
    }

    @Override
    @Transactional(readOnly = true)
    public List<BreakdownRowView> breakdown(UUID reviewId) {
        ImportReview review = loadVisible(reviewId);
        if (!review.isTotalsCheckedOnCurrent()) {
            throw new ConflictException("import_review.totals_not_checked",
                    "Informe primeiro os totais da sua planilha: as quebras aparecem depois da conferência");
        }
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        return rowValues.breakdown(review.getCurrentBatchId(), today).stream()
                .map(r -> new BreakdownRowView(r.dimension(), r.key(), r.rows(), r.principal(), r.balance()))
                .toList();
    }

    // ---------------------------------------------------------------- passo 5: alertas

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AlertView> alerts(UUID reviewId, String type, String status, PageQuery page) {
        ImportReview review = loadVisible(reviewId);
        AlertType t = parseEnum(AlertType.class, type, "import_review.alert_type_invalid");
        AlertStatus st = parseEnum(AlertStatus.class, status, "import_review.alert_status_invalid");
        Sort sort = Sort.by(Sort.Order.desc("priority"), Sort.Order.asc("rowNumber"), Sort.Order.asc("id"));
        return PageResponse.of(alerts.search(review.getCurrentBatchId(), t, st, page.toPageable(sort)), ReviewViews::alert);
    }

    @Override
    public AnalysisView decideAlerts(UUID reviewId, AlertDecisionCommand command) {
        ImportReview review = loadForCommand(reviewId);
        review.requireOpen();
        Guard.requireState(review.getAnalysisStatus() == AnalysisStatus.DONE, "import_review.analysis_pending",
                "Aguarde a análise dos valores");
        Guard.require(command != null, "import_review.decision_required", "Informe a decisão");
        AlertDecision decision = parseEnum(AlertDecision.class, command.decision(), "import_review.decision_invalid");
        Guard.require(decision != null, "import_review.decision_required", "Informe a decisão");
        UUID batchId = review.getCurrentBatchId();
        List<ImportReviewAlert> targets;
        AlertType type = parseEnum(AlertType.class, command.type(), "import_review.alert_type_invalid");
        if (command.alertIds() != null && !command.alertIds().isEmpty()) {
            Set<UUID> ids = new HashSet<>(command.alertIds());
            targets = alerts.findByBatchIdAndIdIn(batchId, ids);
            if (targets.size() != ids.size()) {
                throw new ResourceNotFoundException("import_review_alert", command.alertIds().getFirst());
            }
        } else if (command.allOpen()) {
            targets = alerts.findByBatchIdAndStatusAndTypeIn(batchId, AlertStatus.OPEN,
                    type == null ? List.of(AlertType.values()) : List.of(type));
        } else {
            throw new BusinessRuleException("import_review.decision_target_required",
                    "Escolha os alertas ou decida sobre todos os abertos");
        }
        Instant now = Instant.now(clock);
        UUID actor = currentActor.actorId();
        Map<UUID, ImportReviewAlert> affected = new LinkedHashMap<>();
        for (ImportReviewAlert alert : targets) {
            alert.decide(decision, actor, now, command.justification());
            affected.put(alert.getId(), alert);
        }
        // a exclusão (e a reabertura de uma exclusão) vale para a linha inteira
        Set<Long> rows = targets.stream().filter(a -> a.getRowNumber() > 0).map(ImportReviewAlert::getRowNumber)
                .collect(Collectors.toSet());
        if (!rows.isEmpty() && decision != AlertDecision.CONFIRM) {
            for (ImportReviewAlert sibling : alerts.findByBatchIdAndRowNumberIn(batchId, rows)) {
                boolean propagate = decision == AlertDecision.EXCLUDE ? sibling.getStatus() != AlertStatus.EXCLUDED
                        : sibling.getStatus() == AlertStatus.EXCLUDED;
                if (!affected.containsKey(sibling.getId()) && propagate) {
                    sibling.decide(decision, actor, now, command.justification());
                    affected.put(sibling.getId(), sibling);
                }
            }
        }
        alerts.saveAll(affected.values());
        auditTrail.record(AuditEntry.of("import_review.alerts_decided", RESOURCE, review.getId())
                .creditor(review.getCreditorId())
                .reason(command.justification())
                .meta("decision", decision.name())
                .meta("batchId", batchId)
                .meta("alerts", affected.size())
                .meta("rows", rows.size())
                .meta("selection", command.alertIds() != null && !command.alertIds().isEmpty() ? "ids"
                        : type == null ? "all_open" : "all_open:" + type.name())
                .meta("alertIds", affected.keySet().stream().limit(200).toList()));
        metrics.alertsDecided(decision.name(), affected.size());
        return views.analysis(review);
    }

    // ---------------------------------------------------------------- passo 6: efetivação

    @Override
    public ReviewView commit(UUID reviewId, CommitCommand command) {
        ImportReview review = loadForCommand(reviewId);
        review.requireOpen();
        requireConfirmation(command == null ? null : command.confirmationText());
        ImportBatch preview = preview(review);
        List<ReviewBlocker> blockers = views.blockers(review, preview);
        if (!blockers.isEmpty()) {
            throw new ConflictException("import_review.blocked", blockers.getFirst().message(),
                    Map.of("blockers", blockers.stream().map(Enum::name).toList()));
        }
        String forceReason = command.forceReason();
        BatchCreation.Forcing forcing = creation.checkDuplicate(review.getPortfolioId(), preview.getFileSha256(),
                ImportMode.IMPORT, forceReason != null && !forceReason.isBlank(), forceReason);
        Impact impact = rowValues.impact(preview.getId(), review.getCreditorId());
        UUID actor = currentActor.actorId();
        Instant now = Instant.now(clock);
        BigDecimal threshold = properties.effectiveApprovalThreshold();
        boolean proceed = review.requestCommit(actor, now, blockers, json.toJson(impact), impact.principalToImport(),
                threshold, forcing == null ? null : forcing.reason());
        auditTrail.record(AuditEntry.of("import_review.commit_requested", RESOURCE, review.getId())
                .creditor(review.getCreditorId())
                .change("status", ReviewStatus.OPEN.name(), review.getStatus().name())
                .created("impact", impact)
                .reason(forcing == null ? null : forcing.reason())
                .meta("approvalRequired", review.isApprovalRequired())
                .meta("approvalThreshold", threshold)
                .meta("batchId", preview.getId()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.IMPORT_REVIEW_COMMITTED)
                .creditor(review.getCreditorId()).portfolio(review.getPortfolioId())
                .reference(RESOURCE, review.getId())
                .amount(impact.principalToImport())
                .attr("batchId", preview.getId())
                .attr("rows", impact.rows())
                .attr("rowsToImport", impact.rowsToImport())
                .attr("rowsWithError", impact.rowsWithError())
                .attr("rowsExcluded", impact.rowsExcluded())
                .attr("newTitles", impact.newTitles())
                .attr("existingTitles", impact.existingTitles())
                .attr("approvalRequired", review.isApprovalRequired())
                .attr("previews", review.getPreviews())
                .attr("totalsChecks", review.getTotalsChecks())
                .attr("totalsMismatches", review.getTotalsMismatches())
                .attr("forced", forcing != null)
                .attr("requestedBy", actor));
        outliersReviewed(review, impact);
        metrics.reviewCommitted(review.isApprovalRequired());
        if (proceed) {
            createImport(review, preview, forcing);
        }
        reviews.save(review);
        return views.review(review, preview);
    }

    @Override
    public ReviewView approve(UUID reviewId, ApprovalCommand command) {
        ImportReview review = loadForCommand(reviewId);
        accessScope.requirePermission(Permission.IMPORT_APPROVE);
        requireConfirmation(command == null ? null : command.confirmationText());
        review.approve(currentActor.actorId(), Instant.now(clock), command.note());
        ImportBatch preview = preview(review);
        // o arquivo pode ter sido importado por outro caminho enquanto aguardava
        BatchCreation.Forcing forcing = creation.checkDuplicate(review.getPortfolioId(), preview.getFileSha256(),
                ImportMode.IMPORT, review.getForceReason() != null, review.getForceReason());
        auditTrail.record(AuditEntry.of("import_review.approved", RESOURCE, review.getId())
                .creditor(review.getCreditorId())
                .reason(command.note())
                .meta("requestedBy", review.getCommitRequestedBy())
                .meta("approvalThreshold", review.getApprovalThreshold()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.IMPORT_REVIEW_APPROVED)
                .creditor(review.getCreditorId()).portfolio(review.getPortfolioId())
                .reference(RESOURCE, review.getId())
                .attr("batchId", preview.getId())
                .attr("requestedBy", review.getCommitRequestedBy())
                .attr("approvedBy", review.getDecidedBy())
                .attr("waitMinutes", java.time.Duration.between(review.getCommitRequestedAt(), review.getDecidedAt()).toMinutes()));
        metrics.reviewApproval("approved");
        createImport(review, preview, forcing);
        reviews.save(review);
        return views.review(review, preview);
    }

    @Override
    public ReviewView reject(UUID reviewId, String reason) {
        ImportReview review = loadForCommand(reviewId);
        accessScope.requirePermission(Permission.IMPORT_APPROVE);
        review.reject(currentActor.actorId(), Instant.now(clock), reason);
        reviews.save(review);
        auditTrail.record(AuditEntry.of("import_review.rejected", RESOURCE, review.getId())
                .creditor(review.getCreditorId())
                .change("status", ReviewStatus.AWAITING_APPROVAL.name(), ReviewStatus.REJECTED.name())
                .reason(reason)
                .meta("requestedBy", review.getCommitRequestedBy()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.IMPORT_REVIEW_REJECTED)
                .creditor(review.getCreditorId()).portfolio(review.getPortfolioId())
                .reference(RESOURCE, review.getId())
                .attr("batchId", review.getCurrentBatchId())
                .attr("requestedBy", review.getCommitRequestedBy())
                .attr("rejectedBy", review.getDecidedBy()));
        metrics.reviewApproval("rejected");
        return views.review(review, preview(review));
    }

    @Override
    public ReviewView cancel(UUID reviewId, String reason) {
        ImportReview review = loadForCommand(reviewId);
        String previous = review.getStatus().name();
        Instant now = Instant.now(clock);
        review.cancel(currentActor.actorId(), now, reason);
        ImportBatch preview = preview(review);
        if (!preview.getStatus().isTerminal() && !preview.isCancelRequested()) {
            boolean immediate = preview.requestCancel(now, currentActor.actorId(), "Conferência cancelada: " + reason.strip());
            batches.save(preview);
            if (immediate) {
                jobQueue.cancelPending(ImportBatchJobHandler.TYPE, ImportBatchJobHandler.deduplicationKey(preview.getId(), 0));
                metrics.finished(preview);
            }
        }
        reviews.save(review);
        auditTrail.record(AuditEntry.of("import_review.cancelled", RESOURCE, review.getId())
                .creditor(review.getCreditorId())
                .change("status", previous, ReviewStatus.CANCELLED.name())
                .reason(reason));
        return views.review(review, preview);
    }

    /** Cria o lote de importação: mesmo arquivo, mesmo formato numérico, linhas excluídas puladas. */
    private void createImport(ImportReview review, ImportBatch preview, BatchCreation.Forcing forcing) {
        PortfolioView portfolio = creation.requirePortfolio(review.getPortfolioId());
        ImportBatch batch = creation.create(portfolio, creation.layoutOf(preview), preview.getSource(), ImportMode.IMPORT,
                BatchCreation.storedOf(preview), forcing, preview.getId(),
                new ImportBatch.Options(review.getId(), preview.getNumberFormat(), null));
        review.imported(batch.getId());
        auditTrail.record(AuditEntry.of("import_review.committed", RESOURCE, review.getId())
                .creditor(review.getCreditorId())
                .created("importBatchId", batch.getId())
                .meta("previewBatchId", preview.getId())
                .meta("decidedBy", review.getDecidedBy()));
        LOG.atInfo().addKeyValue("reviewId", review.getId()).addKeyValue("batchId", batch.getId())
                .log("Conferência efetivada: lote de importação criado");
    }

    /** Resumo da revisão de alertas, para inteligência operacional (uma vez por efetivação). */
    private void outliersReviewed(ImportReview review, Impact impact) {
        Map<String, Map<String, Long>> byType = new LinkedHashMap<>();
        for (var count : alerts.countByType(review.getCurrentBatchId())) {
            byType.computeIfAbsent(count.type().name(), t -> new LinkedHashMap<>()).put(count.status().name(), count.count());
        }
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.IMPORT_OUTLIERS_REVIEWED)
                .creditor(review.getCreditorId()).portfolio(review.getPortfolioId())
                .reference(RESOURCE, review.getId())
                .attr("batchId", review.getCurrentBatchId())
                .attr("alerts", review.getAlertsTotal())
                .attr("byType", byType)
                .attr("rowsExcluded", impact.rowsExcluded())
                .attr("requestedBy", currentActor.actorId()));
    }

    // ---------------------------------------------------------------- apoio

    private void requireValidated(ImportReview review, ImportBatch preview) {
        Guard.requireState(review.isFormatConfirmed(), "import_review.format_not_confirmed",
                "Confirme o formato dos valores antes de conferir os totais");
        Guard.requireState(preview.getStatus() == ImportBatchStatus.VALIDATED, "import_review.validation_pending",
                "Aguarde a validação das linhas antes de conferir os totais");
    }

    private void requireConfirmation(String text) {
        if (text == null || !text.strip().toUpperCase(Locale.ROOT).equals(properties.confirmationText().toUpperCase(Locale.ROOT))) {
            throw new BusinessRuleException("import_review.confirmation_mismatch",
                    "Digite %s para confirmar".formatted(properties.confirmationText()));
        }
    }

    private ImportReview loadVisible(UUID reviewId) {
        ImportReview review = reviews.findById(reviewId).orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, reviewId));
        accessScope.requireVisible(RESOURCE, reviewId, review.getCreditorId());
        return review;
    }

    private ImportReview loadForCommand(UUID reviewId) {
        ImportReview review = loadVisible(reviewId);
        accessScope.requireCreditor(review.getCreditorId());
        return review;
    }

    private ImportBatch preview(ImportReview review) {
        return batches.findById(review.getCurrentBatchId())
                .orElseThrow(() -> ResourceNotFoundException.of(BatchCreation.RESOURCE, review.getCurrentBatchId()));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String code) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(code, "Valor inválido: " + value);
        }
    }

    /** Ator atual pode aprovar a conferência (permissão e segregação). */
    static boolean canApprove(Actor actor, ImportReview review) {
        return actor != null && actor.has(Permission.IMPORT_APPROVE) && review.getStatus() == ReviewStatus.AWAITING_APPROVAL
                && !actor.id().equals(review.getCommitRequestedBy()) && !actor.id().equals(review.getCreatedBy());
    }
}
