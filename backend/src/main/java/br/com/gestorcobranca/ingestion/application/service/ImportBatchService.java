package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.shared.paging.ChangePage;
import br.com.gestorcobranca.shared.paging.ChangeQuery;

import br.com.gestorcobranca.ingestion.application.IngestionProperties;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases;
import br.com.gestorcobranca.ingestion.application.port.out.ImportIssueExport;
import br.com.gestorcobranca.ingestion.application.port.out.IssueReportWriter;
import br.com.gestorcobranca.ingestion.domain.model.FileLayout;
import br.com.gestorcobranca.ingestion.domain.model.format.DecisionSource;
import br.com.gestorcobranca.ingestion.domain.model.format.NumberFormat;
import br.com.gestorcobranca.ingestion.domain.model.review.ControlTotalsInput;
import br.com.gestorcobranca.ingestion.domain.model.ImportBatch;
import br.com.gestorcobranca.ingestion.domain.model.ImportBatchStatus;
import br.com.gestorcobranca.ingestion.domain.model.ImportFormat;
import br.com.gestorcobranca.ingestion.domain.model.ImportMode;
import br.com.gestorcobranca.ingestion.domain.model.ImportSource;
import br.com.gestorcobranca.ingestion.domain.model.IssueSeverity;
import br.com.gestorcobranca.ingestion.domain.repository.ImportBatchRepository;
import br.com.gestorcobranca.ingestion.domain.repository.ImportRowIssueRepository;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.PortfolioView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.idempotency.IdempotencyService;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.storage.FileStorage.StoredFile;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
class ImportBatchService implements ImportBatchUseCases {

    static final String RESOURCE = BatchCreation.RESOURCE;
    private static final String IDEMPOTENCY_SCOPE = "ingestion.records";

    private final ImportBatchRepository batches;
    private final ImportRowIssueRepository issues;
    private final ImportIssueExport issueExport;
    private final IssueReportWriter reportWriter;
    private final BatchCreation creation;
    private final FileStorage fileStorage;
    private final JobQueue jobQueue;
    private final IdempotencyService idempotency;
    private final JsonCodec json;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final BusinessEventLogger businessEvents;
    private final IngestionMetrics metrics;
    private final IngestionProperties properties;
    private final Clock clock;

    ImportBatchService(ImportBatchRepository batches, ImportRowIssueRepository issues, ImportIssueExport issueExport,
                       IssueReportWriter reportWriter, BatchCreation creation, FileStorage fileStorage, JobQueue jobQueue,
                       IdempotencyService idempotency, JsonCodec json, AccessScope accessScope,
                       CurrentActor currentActor, AuditTrail auditTrail, AccessLogger accessLogger,
                       BusinessEventLogger businessEvents, IngestionMetrics metrics, IngestionProperties properties,
                       Clock clock) {
        this.batches = batches;
        this.issues = issues;
        this.issueExport = issueExport;
        this.reportWriter = reportWriter;
        this.creation = creation;
        this.fileStorage = fileStorage;
        this.jobQueue = jobQueue;
        this.idempotency = idempotency;
        this.json = json;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- recebimento

    @Override
    public ImportBatchView uploadFile(UploadFileCommand command) {
        PortfolioView portfolio = creation.requirePortfolio(command.portfolioId());
        ImportMode mode = parseMode(command.mode());
        String fileName = creation.fileName(command.fileName());
        ImportFormat format = creation.formatOf(fileName);
        FileLayout layout = creation.resolveLayout(command.layoutId(), portfolio, format);
        NumberFormat preset = NumberFormats.forAllColumns(command.decimalSeparator(), DecisionSource.API);
        StoredFile stored = creation.storeUpload(command.content(), fileName, command.contentType(), command.size(), format);
        BatchCreation.Forcing forcing = creation.checkDuplicate(portfolio.id(), stored.sha256(), mode, command.force(),
                command.reason());
        return ImportViews.batch(creation.create(portfolio, layout, ImportSource.of(format), mode, stored, forcing, null,
                new ImportBatch.Options(null, NumberFormats.toJson(json, preset), null)), json);
    }

    @Override
    public ImportBatchView importRecords(ImportRecordsCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw creation.rejected(new BusinessRuleException("import.idempotency_key_required",
                    "Informe o cabeçalho Idempotency-Key"));
        }
        PortfolioView portfolio = creation.requirePortfolio(command.portfolioId());
        ImportMode mode = parseMode(command.mode());
        List<Map<String, Object>> records = command.records() == null ? List.of() : command.records();
        if (records.isEmpty() || records.size() > properties.maxApiRecords()) {
            throw creation.rejected(new BusinessRuleException("import.records_count_invalid",
                    "Envie de 1 a %d registros por requisição".formatted(properties.maxApiRecords())));
        }
        List<Map<String, Object>> canonical = RecordValues.canonical(records);
        NumberFormat preset = NumberFormats.fromColumns(command.numberFormat(), DecisionSource.API);
        String controlTotals = controlTotalsJson(command.controlTotals());
        byte[] content = json.toJson(canonical).getBytes(StandardCharsets.UTF_8);
        if (content.length > properties.maxFileSize().toBytes()) {
            throw creation.rejected(new BusinessRuleException("import.file_too_large",
                    "Registros excedem o tamanho máximo do lote"));
        }
        String contentHash = BatchCreation.sha256(content);
        String formatJson = NumberFormats.toJson(json, preset);
        String requestHash = BatchCreation.sha256(("%s|%s|%s|%s|%s|%s|%s").formatted(portfolio.id(), mode, command.force(),
                command.reason() == null ? "" : command.reason().strip(), contentHash,
                formatJson == null ? "" : formatJson, controlTotals == null ? "" : controlTotals)
                .getBytes(StandardCharsets.UTF_8));
        UUID batchId = idempotency.execute(IDEMPOTENCY_SCOPE, idempotencyKey.strip(), requestHash, () -> {
            BatchCreation.Forcing forcing = creation.checkDuplicate(portfolio.id(), contentHash, mode, command.force(),
                    command.reason());
            String name = "api-%s.json".formatted(Instant.now(clock).toString().replace(':', '-'));
            StoredFile stored = creation.store(() -> new ByteArrayInputStream(content), name, "application/json",
                    content.length);
            return creation.create(portfolio, FileLayout.standard(ImportFormat.JSON), ImportSource.API, mode, stored,
                    forcing, null, new ImportBatch.Options(null, formatJson, controlTotals)).getId();
        });
        return ImportViews.batch(loadVisible(batchId), json);
    }

    private String controlTotalsJson(ControlTotalsCommand totals) {
        if (totals == null) {
            return null;
        }
        ControlTotalsInput input = new ControlTotalsInput(totals.rows(), totals.principalTotal(), totals.balanceTotal(),
                totals.contractAmountTotal());
        return json.toJson(Map.of("entered", input));
    }

    @Override
    public ImportBatchView reprocess(UUID batchId, ReprocessCommand command) {
        ImportBatch original = loadVisible(batchId);
        PortfolioView portfolio = creation.requirePortfolio(original.getPortfolioId());
        Guard.requireState(original.getStatus().isTerminal(), "import_batch.not_finished",
                "Somente lotes encerrados podem ser reprocessados");
        ImportMode mode = parseMode(command == null ? null : command.mode());
        String reason = command == null ? null : command.reason();
        FileLayout layout = creation.layoutOf(original);
        BatchCreation.Forcing forcing = creation.checkDuplicate(portfolio.id(), original.getFileSha256(), mode,
                reason != null && !reason.isBlank(), reason);
        // reprocessar mantém a interpretação numérica já decidida para o mesmo arquivo
        return ImportViews.batch(creation.create(portfolio, layout, original.getSource(), mode,
                BatchCreation.storedOf(original), forcing, original.getId(),
                new ImportBatch.Options(null, original.getNumberFormat(), null)), json);
    }

    // ---------------------------------------------------------------- cancelamento

    @Override
    public ImportBatchView cancel(UUID batchId, String reason) {
        ImportBatch batch = loadVisible(batchId);
        accessScope.requireCreditor(batch.getCreditorId());
        String previous = batch.getStatus().name();
        boolean immediate = batch.requestCancel(Instant.now(clock), currentActor.actorId(), reason);
        batches.save(batch);
        if (immediate) {
            jobQueue.cancelPending(ImportBatchJobHandler.TYPE, ImportBatchJobHandler.deduplicationKey(batchId, 0));
            metrics.finished(batch);
        }
        auditTrail.record(AuditEntry.of(immediate ? "import_batch.cancelled" : "import_batch.cancel_requested",
                        RESOURCE, batchId)
                .creditor(batch.getCreditorId())
                .reason(reason)
                .change("status", previous, batch.getStatus().name()));
        if (immediate) {
            businessEventCancelled(batch);
        }
        return ImportViews.batch(batch, json);
    }

    /** Cancelado antes de iniciar: nada foi lido nem gravado (o cancelamento em andamento é registrado pelo processamento). */
    private void businessEventCancelled(ImportBatch batch) {
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.IMPORT_CANCELLED)
                .creditor(batch.getCreditorId()).portfolio(batch.getPortfolioId())
                .reference(RESOURCE, batch.getId())
                .attr("source", batch.getSource().name())
                .attr("mode", batch.getMode().name())
                .attr("status", batch.getStatus().name())
                .attr("phase", "queued")
                .attr("rowsRead", 0)
                .attr("requestedBy", batch.getCreatedBy()));
    }

    // ---------------------------------------------------------------- consultas

    @Override
    @Transactional(readOnly = true)
    public ImportBatchView get(UUID batchId) {
        return ImportViews.batch(loadVisible(batchId), json);
    }

    @Override
    @Transactional(readOnly = true)
    public ChangePage<ImportBatchView> changes(UUID creditorId, ChangeQuery query) {
        Guard.require(creditorId != null, "import_batch.creditor_required", "Credor obrigatório");
        accessScope.requireVisible("creditor", creditorId, creditorId);
        return ChangePage.of(batches.findChanges(creditorId, query), query.limit(), ImportBatch::getUpdatedAt,
                ImportBatch::getId, ImportBatch::getVersion, b -> ImportViews.batch(b, json));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ImportBatchView> search(BatchSearch filter, PageQuery page) {
        BatchSearch f = filter == null ? new BatchSearch(null, null, null, null, null, null) : filter;
        if (f.creditorId() != null && !accessScope.canAccessCreditor(f.creditorId())) {
            return new PageResponse<>(List.of(), page.page(), page.size(), 0, 0);
        }
        List<UUID> allowed = accessScope.hasAllCreditors() ? null : List.copyOf(accessScope.restrictCreditors(null));
        ImportBatchRepository.Criteria criteria = new ImportBatchRepository.Criteria(f.creditorId(), f.portfolioId(),
                parseEnum(ImportBatchStatus.class, f.status(), "import_batch.status_invalid"),
                parseEnum(ImportSource.class, f.source(), "import_batch.source_invalid"), f.createdFrom(), f.createdTo());
        return PageResponse.of(batches.search(allowed, criteria,
                page.toPageable(Sort.by(Sort.Direction.DESC, "createdAt"))), b -> ImportViews.batch(b, json));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ImportIssueView> issues(UUID batchId, String severity, PageQuery page) {
        loadVisible(batchId);
        IssueSeverity level = parseEnum(IssueSeverity.class, severity, "import_issue.severity_invalid");
        Sort sort = Sort.by("rowNumber", "severity", "id");
        var result = level == null ? issues.findByBatchId(batchId, page.toPageable(sort))
                : issues.findByBatchIdAndSeverity(batchId, level, page.toPageable(sort));
        return PageResponse.of(result, ImportViews::issue);
    }

    @Override
    @Transactional(readOnly = true)
    public void exportIssues(UUID batchId, OutputStream output) {
        ImportBatch batch = loadVisible(batchId);
        accessLogger.log(AccessEvent.of(AccessEventType.DATA_EXPORTED, currentActor.get(),
                Map.of("resource", RESOURCE, "resourceId", batchId.toString(), "export", "import_issues",
                        "issues", String.valueOf(batch.getIssuesRecorded()))));
        try (IssueReportWriter.Report report = reportWriter.open(output)) {
            issueExport.forEachIssue(batchId, report::write);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public DownloadableFile originalFile(UUID batchId) {
        ImportBatch batch = loadVisible(batchId);
        accessScope.requirePermission(Permission.DEBTOR_SENSITIVE_VIEW);
        accessLogger.log(AccessEvent.of(AccessEventType.FILE_DOWNLOADED, currentActor.get(),
                Map.of("resource", RESOURCE, "resourceId", batchId.toString(), "category", FileCategory.IMPORT.name())));
        return new DownloadableFile(batch.getFileName(),
                batch.getFileContentType() == null ? "application/octet-stream" : batch.getFileContentType(),
                batch.getFileSize(), fileStorage.open(batch.getFileKey()));
    }

    // ---------------------------------------------------------------- apoio

    private ImportBatch loadVisible(UUID batchId) {
        ImportBatch batch = batches.findById(batchId).orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, batchId));
        accessScope.requireVisible(RESOURCE, batchId, batch.getCreditorId());
        return batch;
    }

    static ImportMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return ImportMode.IMPORT;
        }
        return switch (mode.strip().toUpperCase(Locale.ROOT)) {
            case "IMPORT" -> ImportMode.IMPORT;
            case "VALIDATE_ONLY", "PREVIEW", "DRY_RUN" -> ImportMode.VALIDATE_ONLY;
            default -> throw new BusinessRuleException("import.mode_invalid", "Modo deve ser IMPORT ou VALIDATE_ONLY");
        };
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
}
