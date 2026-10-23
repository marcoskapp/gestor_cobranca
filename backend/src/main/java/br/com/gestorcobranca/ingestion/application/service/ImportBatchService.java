package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.application.IngestionProperties;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases;
import br.com.gestorcobranca.ingestion.application.port.out.ImportIssueExport;
import br.com.gestorcobranca.ingestion.application.port.out.IssueReportWriter;
import br.com.gestorcobranca.ingestion.domain.model.FileLayout;
import br.com.gestorcobranca.ingestion.domain.model.ImportBatch;
import br.com.gestorcobranca.ingestion.domain.model.ImportBatchStatus;
import br.com.gestorcobranca.ingestion.domain.model.ImportFileException;
import br.com.gestorcobranca.ingestion.domain.model.ImportFormat;
import br.com.gestorcobranca.ingestion.domain.model.ImportLayout;
import br.com.gestorcobranca.ingestion.domain.model.ImportMode;
import br.com.gestorcobranca.ingestion.domain.model.ImportSource;
import br.com.gestorcobranca.ingestion.domain.model.IssueSeverity;
import br.com.gestorcobranca.ingestion.domain.repository.ImportBatchRepository;
import br.com.gestorcobranca.ingestion.domain.repository.ImportLayoutRepository;
import br.com.gestorcobranca.ingestion.domain.repository.ImportRowIssueRepository;
import br.com.gestorcobranca.ingestion.domain.service.FileSignature;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.PortfolioView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.DomainException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
class ImportBatchService implements ImportBatchUseCases {

    static final String RESOURCE = "import_batch";
    private static final Logger LOG = LoggerFactory.getLogger(ImportBatchService.class);
    private static final String IDEMPOTENCY_SCOPE = "ingestion.records";
    private static final int FILE_NAME_MAX = 200;
    private static final List<ImportBatchStatus> IN_FLIGHT = List.of(ImportBatchStatus.RECEIVED, ImportBatchStatus.PROCESSING);
    private static final List<ImportBatchStatus> IMPORTED = List.of(ImportBatchStatus.COMPLETED,
            ImportBatchStatus.COMPLETED_WITH_ERRORS);

    private final ImportBatchRepository batches;
    private final ImportLayoutRepository layouts;
    private final ImportRowIssueRepository issues;
    private final ImportIssueExport issueExport;
    private final IssueReportWriter reportWriter;
    private final PortfolioUseCases portfolios;
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

    ImportBatchService(ImportBatchRepository batches, ImportLayoutRepository layouts, ImportRowIssueRepository issues,
                       ImportIssueExport issueExport, IssueReportWriter reportWriter, PortfolioUseCases portfolios,
                       FileStorage fileStorage, JobQueue jobQueue, IdempotencyService idempotency, JsonCodec json,
                       AccessScope accessScope, CurrentActor currentActor, AuditTrail auditTrail,
                       AccessLogger accessLogger, BusinessEventLogger businessEvents, IngestionMetrics metrics,
                       IngestionProperties properties, Clock clock) {
        this.batches = batches;
        this.layouts = layouts;
        this.issues = issues;
        this.issueExport = issueExport;
        this.reportWriter = reportWriter;
        this.portfolios = portfolios;
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
        PortfolioView portfolio = requirePortfolio(command.portfolioId());
        ImportMode mode = parseMode(command.mode());
        String fileName = fileName(command.fileName());
        ImportFormat format = formatOf(fileName);
        long maxSize = properties.maxFileSize().toBytes();
        if (command.size() > maxSize) {
            throw rejected(new BusinessRuleException("import.file_too_large",
                    "Arquivo excede o limite de %d MB".formatted(properties.maxFileSize().toMegabytes())));
        }
        FileLayout layout = resolveLayout(command.layoutId(), portfolio, format);
        Digest digest = digest(command.content(), maxSize, format);
        Forcing forcing = checkDuplicate(portfolio.id(), digest.sha256(), mode, command.force(), command.reason());
        StoredFile stored = store(command.content(), fileName, command.contentType(), digest.size());
        Guard.require(stored.sha256().equals(digest.sha256()), "import.file_changed",
                "Conteúdo do arquivo mudou durante o envio");
        return create(portfolio, layout, ImportSource.of(format), mode, stored, forcing, null);
    }

    @Override
    public ImportBatchView importRecords(ImportRecordsCommand command, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw rejected(new BusinessRuleException("import.idempotency_key_required",
                    "Informe o cabeçalho Idempotency-Key"));
        }
        PortfolioView portfolio = requirePortfolio(command.portfolioId());
        ImportMode mode = parseMode(command.mode());
        List<Map<String, String>> records = command.records() == null ? List.of() : command.records();
        if (records.isEmpty() || records.size() > properties.maxApiRecords()) {
            throw rejected(new BusinessRuleException("import.records_count_invalid",
                    "Envie de 1 a %d registros por requisição".formatted(properties.maxApiRecords())));
        }
        byte[] content = json.toJson(records).getBytes(StandardCharsets.UTF_8);
        if (content.length > properties.maxFileSize().toBytes()) {
            throw rejected(new BusinessRuleException("import.file_too_large", "Registros excedem o tamanho máximo do lote"));
        }
        String contentHash = sha256(content);
        String requestHash = sha256(("%s|%s|%s|%s|%s").formatted(portfolio.id(), mode, command.force(),
                command.reason() == null ? "" : command.reason().strip(), contentHash).getBytes(StandardCharsets.UTF_8));
        UUID batchId = idempotency.execute(IDEMPOTENCY_SCOPE, idempotencyKey.strip(), requestHash, () -> {
            Forcing forcing = checkDuplicate(portfolio.id(), contentHash, mode, command.force(), command.reason());
            String name = "api-%s.json".formatted(Instant.now(clock).toString().replace(':', '-'));
            StoredFile stored = store(() -> new ByteArrayInputStream(content), name, "application/json", content.length);
            return create(portfolio, FileLayout.standard(ImportFormat.JSON), ImportSource.API, mode, stored, forcing, null)
                    .id();
        });
        return ImportViews.batch(loadVisible(batchId));
    }

    @Override
    public ImportBatchView reprocess(UUID batchId, ReprocessCommand command) {
        ImportBatch original = loadVisible(batchId);
        PortfolioView portfolio = requirePortfolio(original.getPortfolioId());
        Guard.requireState(original.getStatus().isTerminal(), "import_batch.not_finished",
                "Somente lotes encerrados podem ser reprocessados");
        ImportMode mode = parseMode(command == null ? null : command.mode());
        String reason = command == null ? null : command.reason();
        FileLayout layout = original.getLayoutId() == null ? FileLayout.standard(original.getSource().format())
                : layouts.findById(original.getLayoutId()).orElseThrow(
                        () -> ResourceNotFoundException.of("import_layout", original.getLayoutId())).toFileLayout();
        Forcing forcing = checkDuplicate(portfolio.id(), original.getFileSha256(), mode, reason != null && !reason.isBlank(),
                reason);
        StoredFile stored = new StoredFile(null, original.getFileKey(), original.getFileName(), original.getFileContentType(),
                original.getFileSize(), original.getFileSha256());
        return create(portfolio, layout, original.getSource(), mode, stored, forcing, original.getId());
    }

    private ImportBatchView create(PortfolioView portfolio, FileLayout layout, ImportSource source, ImportMode mode,
                                   StoredFile stored, Forcing forcing, UUID reprocessOf) {
        ImportBatch batch = new ImportBatch(portfolio.creditorId(), portfolio.id(), layout, source, mode,
                new ImportBatch.StoredContent(stored.key(), stored.originalName(), stored.contentType(), stored.size(),
                        stored.sha256()),
                forcing == null ? null : new ImportBatch.Forcing(forcing.duplicateOf(), forcing.reason()), reprocessOf);
        batches.save(batch);
        jobQueue.enqueue(ImportBatchJobHandler.TYPE, new ImportBatchJobHandler.Payload(batch.getId(), 0), null,
                ImportBatchJobHandler.deduplicationKey(batch.getId(), 0));
        AuditEntry audit = AuditEntry.of("import_batch.received", RESOURCE, batch.getId())
                .creditor(batch.getCreditorId())
                .created("portfolioId", batch.getPortfolioId())
                .created("source", batch.getSource())
                .created("mode", batch.getMode())
                .created("layoutCode", batch.getLayoutCode())
                .created("layoutVersion", batch.getLayoutVersion())
                .created("fileSize", batch.getFileSize())
                .created("fileSha256", batch.getFileSha256())
                .meta("reprocessOfBatchId", reprocessOf);
        if (forcing != null) {
            audit.reason(forcing.reason()).meta("duplicateOfBatchId", forcing.duplicateOf()).meta("forced", true);
        }
        auditTrail.record(audit);
        metrics.received(batch);
        LOG.atInfo().addKeyValue("batchId", batch.getId()).addKeyValue("source", source.name())
                .addKeyValue("mode", mode.name()).addKeyValue("fileSize", batch.getFileSize())
                .log("Lote de importação recebido");
        return ImportViews.batch(batch);
    }

    /**
     * Mesmo conteúdo já importado com sucesso na carteira: recusa, salvo confirmação com motivo.
     * Pré-visualização nunca é bloqueada. Mesmo conteúdo em importação simultânea: sempre recusa.
     */
    private Forcing checkDuplicate(UUID portfolioId, String sha256, ImportMode mode, boolean force, String reason) {
        if (mode != ImportMode.IMPORT) {
            return null;
        }
        List<ImportBatch> inFlight = batches.findByPortfolioIdAndFileSha256AndModeAndStatusInOrderByCreatedAtDesc(
                portfolioId, sha256, ImportMode.IMPORT, IN_FLIGHT);
        if (!inFlight.isEmpty()) {
            throw rejected(new ConflictException("import.already_in_progress",
                    "O mesmo arquivo já está em importação nesta carteira",
                    Map.of("batchId", inFlight.getFirst().getId())));
        }
        List<ImportBatch> imported = batches.findByPortfolioIdAndFileSha256AndModeAndStatusInOrderByCreatedAtDesc(
                portfolioId, sha256, ImportMode.IMPORT, IMPORTED);
        if (imported.isEmpty()) {
            return null;
        }
        if (!force) {
            throw rejected(new ConflictException("import.duplicate_file",
                    "Este arquivo já foi importado nesta carteira; confirme o reprocessamento informando o motivo",
                    Map.of("batchId", imported.getFirst().getId())));
        }
        if (reason == null || reason.isBlank()) {
            throw rejected(new BusinessRuleException("import.force_reason_required",
                    "Informe o motivo do reprocessamento de um arquivo já importado"));
        }
        return new Forcing(imported.getFirst().getId(), reason.strip());
    }

    private record Forcing(UUID duplicateOf, String reason) {
    }

    private FileLayout resolveLayout(UUID layoutId, PortfolioView portfolio, ImportFormat format) {
        if (layoutId == null) {
            return FileLayout.standard(format);
        }
        ImportLayout layout = layouts.findById(layoutId)
                .orElseThrow(() -> ResourceNotFoundException.of("import_layout", layoutId));
        accessScope.requireVisible("import_layout", layoutId, layout.getCreditorId());
        Guard.require(layout.isActive(), "import.layout_inactive", "Layout inativo: use a versão vigente");
        Guard.require(layout.appliesTo(portfolio.creditorId(), portfolio.id()), "import.layout_not_applicable",
                "Layout não pertence ao credor ou à carteira do lote");
        if (layout.getFormat() != format) {
            throw rejected(new BusinessRuleException("import.format_mismatch",
                    "Extensão do arquivo não corresponde ao formato do layout (%s)".formatted(layout.getFormat())));
        }
        return layout.toFileLayout();
    }

    private PortfolioView requirePortfolio(UUID portfolioId) {
        Guard.require(portfolioId != null, "import_batch.portfolio_required", "Carteira obrigatória");
        PortfolioView portfolio = portfolios.requireAcceptingImports(portfolioId);
        accessScope.requireCreditor(portfolio.creditorId());
        return portfolio;
    }

    /** Hash e tamanho numa passagem, com limite de tamanho e verificação do tipo pelo conteúdo. */
    private Digest digest(FileContent content, long maxSize, ImportFormat format) {
        MessageDigest sha = sha256Digest();
        long size = 0;
        try (InputStream in = new DigestInputStream(content.open(), sha)) {
            byte[] head = in.readNBytes(FileSignature.HEAD_SIZE);
            try {
                FileSignature.check(format, head);
            } catch (ImportFileException e) {
                throw rejected(e);
            }
            size = head.length;
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                size += read;
                if (size > maxSize) {
                    throw rejected(new BusinessRuleException("import.file_too_large",
                            "Arquivo excede o limite de %d MB".formatted(properties.maxFileSize().toMegabytes())));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o arquivo enviado", e);
        }
        return new Digest(HexFormat.of().formatHex(sha.digest()), size);
    }

    private record Digest(String sha256, long size) {
    }

    private StoredFile store(FileContent content, String name, String contentType, long size) {
        StoredFile stored;
        try (InputStream in = content.open()) {
            stored = fileStorage.store(FileCategory.IMPORT, name, contentType, in, size);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o arquivo enviado", e);
        }
        deleteOnRollback(stored.key());
        return stored;
    }

    /** O armazenamento não participa da transação: arquivo de lote não criado não fica órfão. */
    private void deleteOnRollback(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        fileStorage.delete(key);
                    } catch (RuntimeException e) {
                        LOG.atError().addKeyValue("storageKey", key).setCause(e)
                                .log("Arquivo de importação de transação desfeita não pôde ser removido");
                    }
                }
            }
        });
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
        return ImportViews.batch(batch);
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
        return ImportViews.batch(loadVisible(batchId));
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
                page.toPageable(Sort.by(Sort.Direction.DESC, "createdAt"))), ImportViews::batch);
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

    private <E extends RuntimeException> E rejected(E e) {
        metrics.rejected(e instanceof DomainException d ? d.code() : "unknown");
        return e;
    }

    private static ImportMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return ImportMode.IMPORT;
        }
        return switch (mode.strip().toUpperCase(Locale.ROOT)) {
            case "IMPORT" -> ImportMode.IMPORT;
            case "VALIDATE_ONLY", "PREVIEW", "DRY_RUN" -> ImportMode.VALIDATE_ONLY;
            default -> throw new BusinessRuleException("import.mode_invalid", "Modo deve ser IMPORT ou VALIDATE_ONLY");
        };
    }

    private String fileName(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isEmpty()) {
            throw rejected(new BusinessRuleException("import.file_name_required", "Nome do arquivo obrigatório"));
        }
        return name.length() <= FILE_NAME_MAX ? name : name.substring(name.length() - FILE_NAME_MAX);
    }

    private ImportFormat formatOf(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv") || lower.endsWith(".txt")) {
            return ImportFormat.CSV;
        }
        if (lower.endsWith(".xlsx")) {
            return ImportFormat.XLSX;
        }
        throw rejected(new BusinessRuleException("import.file_type_invalid", "Envie um arquivo .csv ou .xlsx"));
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

    private static String sha256(byte[] content) {
        return HexFormat.of().formatHex(sha256Digest().digest(content));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
