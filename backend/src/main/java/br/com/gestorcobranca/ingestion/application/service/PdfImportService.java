package br.com.gestorcobranca.ingestion.application.service;

import br.com.gestorcobranca.ingestion.application.PdfImportProperties;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.DownloadableFile;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.ReviewView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.StartReviewCommand;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases;
import br.com.gestorcobranca.ingestion.application.port.out.CanonicalFileWriter;
import br.com.gestorcobranca.ingestion.application.port.out.PdfDocumentReader;
import br.com.gestorcobranca.ingestion.application.port.out.PdfDocumentReader.PdfInspection;
import br.com.gestorcobranca.ingestion.application.port.out.PdfDocumentReader.RenderedPage;
import br.com.gestorcobranca.ingestion.domain.model.CanonicalField;
import br.com.gestorcobranca.ingestion.domain.model.ImportFileException;
import br.com.gestorcobranca.ingestion.domain.model.pdf.CanonicalRecord;
import br.com.gestorcobranca.ingestion.domain.model.pdf.ExtractionMap;
import br.com.gestorcobranca.ingestion.domain.model.pdf.Finding;
import br.com.gestorcobranca.ingestion.domain.model.pdf.MapOrigin;
import br.com.gestorcobranca.ingestion.domain.model.pdf.MapProblem;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfImport;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfImportChannel;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfImportStatus;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfLayoutTemplate;
import br.com.gestorcobranca.ingestion.domain.model.pdf.RecordDecisions;
import br.com.gestorcobranca.ingestion.domain.model.pdf.RecordOrigin;
import br.com.gestorcobranca.ingestion.domain.repository.PdfImportRepository;
import br.com.gestorcobranca.ingestion.domain.repository.PdfLayoutTemplateRepository;
import br.com.gestorcobranca.ingestion.domain.repository.PdfMapRevisionRepository;
import br.com.gestorcobranca.ingestion.domain.service.pdf.ExtractionMapValidator;
import br.com.gestorcobranca.ingestion.domain.service.pdf.LayoutFingerprint;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.PortfolioView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.SkippedRecords;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.RecordDecisionResult;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.RecordFacets;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.RecordFilter;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.ingestion.domain.service.pdf.RecordDecisionPolicy;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Casos de uso da importação PDF inteligente ({@code docs/tecnico/desenho/importacao-pdf.md}). */
@Service
@Transactional
class PdfImportService implements PdfImportUseCases {

    static final String RESOURCE = "pdf_import";
    static final String TEMPLATE_RESOURCE = "pdf_layout_template";
    private static final Logger LOG = LoggerFactory.getLogger(PdfImportService.class);
    private static final List<PdfImportStatus> ACTIVE = List.of(PdfImportStatus.RECEIVED, PdfImportStatus.MAPPING,
            PdfImportStatus.AWAITING_MAP, PdfImportStatus.AWAITING_VALIDATION, PdfImportStatus.APPROVED);
    private static final int JUSTIFICATION_MIN = 5;
    /** Títulos escolhidos pelo número numa decisão (acima disso, a seleção vai pelo critério do filtro). */
    static final int BULK_MAX = 1000;
    private static final int IDEMPOTENCY_MAX = 160;
    /** Problemas de forma do mapa editado que nem chegam a ser guardados (tamanho, caracteres de controle, esquema). */
    private static final Set<String> REJECTED_MAP_CODES = Set.of("pdf_map.too_many", "pdf_map.text_too_long",
            "pdf_map.text_invalid", "pdf_map.schema_version");

    private final PdfImportRepository imports;
    private final PdfMapRevisionRepository revisions;
    private final PdfLayoutTemplateRepository templates;
    private final BatchCreation creation;
    private final PdfDocumentReader reader;
    private final PdfAnalysis analysis;
    private final PdfMapApplier applier;
    private final PdfMapProposer proposer;
    private final ImportReviewUseCases reviews;
    private final CanonicalFileWriter fileWriter;
    private final FileStorage storage;
    private final JobQueue jobQueue;
    private final PdfImportViews views;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final PdfImportMetrics metrics;
    private final PdfImportProperties properties;
    private final RecordDecisionPolicy decisionPolicy = new RecordDecisionPolicy();
    private final Clock clock;
    private final ExtractionMapValidator validator = new ExtractionMapValidator();

    PdfImportService(PdfImportRepository imports, PdfMapRevisionRepository revisions,
                     PdfLayoutTemplateRepository templates, BatchCreation creation, PdfDocumentReader reader,
                     PdfAnalysis analysis, PdfMapApplier applier, PdfMapProposer proposer, ImportReviewUseCases reviews,
                     CanonicalFileWriter fileWriter, FileStorage storage, JobQueue jobQueue, PdfImportViews views,
                     AccessScope accessScope, CurrentActor currentActor, AccessLogger accessLogger,
                     AuditTrail auditTrail, BusinessEventLogger businessEvents, PdfImportMetrics metrics,
                     PdfImportProperties properties, Clock clock) {
        this.imports = imports;
        this.revisions = revisions;
        this.templates = templates;
        this.creation = creation;
        this.reader = reader;
        this.analysis = analysis;
        this.applier = applier;
        this.proposer = proposer;
        this.reviews = reviews;
        this.fileWriter = fileWriter;
        this.storage = storage;
        this.jobQueue = jobQueue;
        this.views = views;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    // ================================================================== envio

    @Override
    public PdfImportView upload(UploadPdfCommand command) {
        PortfolioView portfolio = creation.requirePortfolio(command.portfolioId());
        PdfImportChannel channel = parseChannel(command.channel());
        String idempotencyKey = idempotencyKey(command.idempotencyKey(), channel);
        String fileName = creation.fileName(command.fileName());
        try {
            byte[] content = read(command);
            String sha256 = sha256(content);
            if (idempotencyKey != null) {
                var existing = imports.findByCreditorIdAndIdempotencyKey(portfolio.creditorId(), idempotencyKey);
                if (existing.isPresent()) {
                    Guard.require(existing.get().getFileSha256().equals(sha256)
                                    && existing.get().getPortfolioId().equals(portfolio.id()),
                            "pdf_import.idempotency_conflict", "Chave de idempotência já usada com outro arquivo ou carteira");
                    return get(existing.get().getId());
                }
            }
            List<PdfImport> duplicates = imports.findByPortfolioIdAndFileSha256AndStatusIn(portfolio.id(), sha256, ACTIVE);
            if (!duplicates.isEmpty()) {
                throw new ConflictException("pdf_import.duplicate_file",
                        "Este PDF já foi enviado para esta carteira (em andamento ou aprovado)",
                        Map.of("pdfImportId", duplicates.getFirst().getId(), "status", duplicates.getFirst().getStatus().name()));
            }
            PdfInspection inspection = reader.inspect(content, properties.maxPages());
            boolean textLayer = inspection.text().hasTextLayer();
            if (!textLayer && inspection.pages() > properties.maxVisualPages()) {
                throw new ImportFileException("pdf_import.too_many_pages_visual",
                        "PDF sem camada de texto (digitalizado) com %d páginas: o limite da leitura visual é %d"
                                .formatted(inspection.pages(), properties.maxVisualPages()));
            }
            StoredFile stored = creation.store(() -> new ByteArrayInputStream(content), fileName, "application/pdf",
                    content.length);
            Guard.require(stored.sha256().equals(sha256), "import.file_changed", "Conteúdo do arquivo mudou durante o envio");
            PdfImport pdf = imports.save(new PdfImport(portfolio.creditorId(), portfolio.id(), channel, fileName,
                    content.length, sha256, stored.key(), inspection.pages(), textLayer, idempotencyKey));
            analysis.remember(pdf, inspection.text());
            jobQueue.enqueue(PdfMappingJobHandler.TYPE, new PdfMappingJobHandler.Payload(pdf.getId(), true), null,
                    PdfMappingJobHandler.deduplicationKey(pdf.getId()));
            auditTrail.record(AuditEntry.of("pdf_import.received", RESOURCE, pdf.getId()).creditor(pdf.getCreditorId())
                    .created("portfolioId", pdf.getPortfolioId()).created("fileSha256", sha256)
                    .created("pages", inspection.pages()).created("textLayer", textLayer)
                    .meta("channel", channel.name()).meta("encrypted", inspection.encrypted()));
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.PDF_IMPORT_RECEIVED)
                    .creditor(pdf.getCreditorId()).portfolio(pdf.getPortfolioId()).reference(RESOURCE, pdf.getId())
                    .channel(channel.name()).attr("pages", inspection.pages()).attr("textLayer", textLayer)
                    .attr("fileSize", content.length));
            metrics.received(pdf);
            LOG.atInfo().addKeyValue("pdfImportId", pdf.getId()).addKeyValue("pages", inspection.pages())
                    .addKeyValue("textLayer", textLayer).addKeyValue("channel", channel).log("PDF recebido para importação");
            return get(pdf.getId());
        } catch (ImportFileException e) {
            metrics.rejected(e.code());
            throw e;
        }
    }

    private byte[] read(UploadPdfCommand command) {
        long max = properties.maxFileSize().toBytes();
        if (command.size() > max) {
            throw tooLarge(max);
        }
        try (InputStream in = command.content().open()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > max) {
                    throw tooLarge(max);
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o PDF enviado", e);
        }
    }

    private static ImportFileException tooLarge(long max) {
        return new ImportFileException("pdf_import.file_too_large",
                "O PDF passa do limite de %d MB".formatted(max / (1024 * 1024)));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PdfImportChannel parseChannel(String raw) {
        if (raw == null || raw.isBlank()) {
            return PdfImportChannel.SCREEN;
        }
        try {
            return PdfImportChannel.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("pdf_import.channel_invalid", "Canal inválido");
        }
    }

    private static String idempotencyKey(String raw, PdfImportChannel channel) {
        if (raw == null || raw.isBlank()) {
            Guard.require(channel != PdfImportChannel.PUBLIC_API, "pdf_import.idempotency_key_required",
                    "Informe o cabeçalho Idempotency-Key");
            return null;
        }
        String key = raw.strip();
        Guard.require(key.length() >= 8 && key.length() <= IDEMPOTENCY_MAX && key.matches("[A-Za-z0-9._:-]+"),
                "pdf_import.idempotency_key_invalid", "Chave de idempotência inválida");
        return key;
    }

    // ================================================================== consultas

    @Override
    @Transactional(readOnly = true)
    public PdfImportView get(UUID id) {
        PdfImport pdf = loadVisible(id);
        return view(pdf, analyzable(pdf) ? analysis.analyze(pdf) : null);
    }

    private PdfImportView view(PdfImport pdf, PdfAnalysis.Result result) {
        RecordDecisions decisions = analysis.decisions(pdf);
        PdfAnalysis.Summary summary = result != null ? analysis.summary(result, decisions) : analysis.parseSummary(pdf.getSummary());
        PdfLayoutTemplate template = pdf.getTemplateId() == null ? null : templates.findById(pdf.getTemplateId()).orElse(null);
        return views.full(pdf, result, decisions, summary, revisions.findByPdfImportIdOrderByRevisionAsc(pdf.getId()),
                template, blockers(pdf, result, decisions));
    }

    /** Há o que analisar: mapa presente, arquivo (ou leitura visual) guardado e importação não descartada. */
    private static boolean analyzable(PdfImport pdf) {
        boolean source = pdf.getMapOrigin() == MapOrigin.VISUAL ? pdf.getVisualResult() != null : pdf.getFileKey() != null;
        return source && (pdf.getStatus() == PdfImportStatus.AWAITING_VALIDATION
                || pdf.getStatus() == PdfImportStatus.AWAITING_MAP || pdf.getStatus() == PdfImportStatus.APPROVED)
                && (pdf.getMap() != null || pdf.getStatus() == PdfImportStatus.AWAITING_MAP);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PdfImportSummaryView> search(PdfImportSearch filter, PageQuery page) {
        PdfImportSearch f = filter == null ? new PdfImportSearch(null, null, null) : filter;
        if (f.creditorId() != null && !accessScope.canAccessCreditor(f.creditorId())) {
            return new PageResponse<>(List.of(), page.page(), page.size(), 0, 0);
        }
        List<UUID> allowed = accessScope.hasAllCreditors() ? null : List.copyOf(accessScope.restrictCreditors(null));
        List<PdfImportStatus> statuses = f.status() == null || f.status().isBlank() ? List.of()
                : Arrays.stream(f.status().split(",")).map(String::strip).filter(v -> !v.isEmpty())
                .map(PdfImportService::parseStatus).toList();
        var result = imports.search(allowed, new PdfImportRepository.Criteria(f.creditorId(), f.portfolioId(), statuses),
                page.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(result, p -> views.summary(p, analysis.parseSummary(p.getSummary())));
    }

    private static PdfImportStatus parseStatus(String raw) {
        try {
            return PdfImportStatus.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("pdf_import.status_invalid", "Situação inválida: " + raw);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PdfRecordView> records(UUID id, RecordFilter filter, PageQuery page) {
        PdfImport pdf = loadVisible(id);
        Guard.requireState(analyzable(pdf) && pdf.getMap() != null, "pdf_import.no_records",
                "A importação ainda não tem títulos para mostrar");
        PdfAnalysis.Result result = analysis.analyze(pdf);
        RecordDecisions decisions = analysis.decisions(pdf);
        boolean unmasked = canSeeValues();
        PdfRecordQuery query = query(result, decisions, unmasked);
        List<CanonicalRecord> selected = query.select(filter);
        int from = Math.min(selected.size(), page.page() * page.size());
        int to = Math.min(selected.size(), from + page.size());
        List<PdfRecordView> content = new ArrayList<>();
        for (CanonicalRecord r : selected.subList(from, to)) {
            content.add(views.record(r, query.findingsByRecord().getOrDefault(r.ordinal(), List.of()), decisions,
                    !unmasked, analysis.lowConfidence(r)));
        }
        if (unmasked && !content.isEmpty()) {
            accessLogger.log(AccessEvent.of(AccessEventType.SENSITIVE_DATA_VIEWED, currentActor.get(), Map.of(
                    "resource", RESOURCE, "resourceId", id.toString(), "field", "records",
                    "page", String.valueOf(page.page()), "count", String.valueOf(content.size()))));
        }
        int totalPages = page.size() == 0 ? 0 : (selected.size() + page.size() - 1) / page.size();
        return new PageResponse<>(content, page.page(), page.size(), selected.size(), totalPages);
    }

    @Override
    @Transactional(readOnly = true)
    public RecordFacets recordFacets(UUID id, RecordFilter filter) {
        PdfImport pdf = loadVisible(id);
        Guard.requireState(analyzable(pdf) && pdf.getMap() != null, "pdf_import.no_records",
                "A importação ainda não tem títulos para mostrar");
        boolean unmasked = canSeeValues();
        RecordFacets facets = query(analysis.analyze(pdf), analysis.decisions(pdf), unmasked).facets(filter);
        if (unmasked && !facets.debtors().isEmpty()) {
            accessLogger.log(AccessEvent.of(AccessEventType.SENSITIVE_DATA_VIEWED, currentActor.get(), Map.of(
                    "resource", RESOURCE, "resourceId", id.toString(), "field", "debtor_names",
                    "count", String.valueOf(facets.debtors().size()))));
        }
        return facets;
    }

    private PdfRecordQuery query(PdfAnalysis.Result result, RecordDecisions decisions, boolean personal) {
        return new PdfRecordQuery(result.records(), result.report().findings(), decisions, analysis::lowConfidence,
                personal);
    }

    /** Quem conduz a importação (ou pode ver dado sensível) vê os valores lidos e as páginas. */
    private boolean canSeeValues() {
        Actor actor = currentActor.get();
        return actor.has(Permission.IMPORT_EXECUTE) || actor.has(Permission.DEBTOR_SENSITIVE_VIEW);
    }

    @Override
    @Transactional(readOnly = true)
    public PageImage page(UUID id, int page) {
        PdfImport pdf = loadVisible(id);
        if (!canSeeValues()) {
            accessScope.requirePermission(Permission.DEBTOR_SENSITIVE_VIEW);
        }
        Guard.require(page >= 1 && page <= pdf.getPageCount(), "pdf_import.page_not_found", "Página inexistente");
        RenderedPage rendered = reader.render(analysis.content(pdf), page, properties.screenDpi());
        accessLogger.log(AccessEvent.of(AccessEventType.SENSITIVE_DATA_VIEWED, currentActor.get(), Map.of(
                "resource", RESOURCE, "resourceId", id.toString(), "field", "page_image", "page", String.valueOf(page))));
        return new PageImage(page, "image/png", rendered.png());
    }

    @Override
    @Transactional(readOnly = true)
    public DownloadableFile originalFile(UUID id) {
        PdfImport pdf = loadVisible(id);
        accessScope.requirePermission(Permission.DEBTOR_SENSITIVE_VIEW);
        Guard.require(pdf.getFileKey() != null, "pdf_import.file_deleted", "O arquivo já foi descartado pela retenção");
        accessLogger.log(AccessEvent.of(AccessEventType.FILE_DOWNLOADED, currentActor.get(),
                Map.of("resource", RESOURCE, "resourceId", id.toString(), "category", FileCategory.IMPORT.name())));
        return new DownloadableFile(pdf.getFileName(), "application/pdf", pdf.getFileSize(), storage.open(pdf.getFileKey()));
    }

    // ================================================================== mapa e decisões

    @Override
    public PdfImportView updateMap(UUID id, UpdateMapCommand command) {
        PdfImport pdf = loadForCommand(id);
        Guard.requireState(pdf.getStatus().editable(), "pdf_import.not_editable", "A importação não aceita alterações neste estado");
        Guard.require(pdf.getMapOrigin() != MapOrigin.VISUAL, "pdf_import.visual_map_not_editable",
                "PDF lido visualmente (sem camada de texto): o mapa é só descritivo; exclua ou confirme os títulos");
        requireRevision(pdf, command.revision());
        Guard.require(command.map() != null, "pdf_map.missing", "Informe o mapa");
        ExtractionMap map = analysis.parseMap(command.map());
        List<MapProblem> rejected = validator.validate(map).stream().filter(p -> REJECTED_MAP_CODES.contains(p.code())).toList();
        if (!rejected.isEmpty()) {
            throw new BusinessRuleException("pdf_map.invalid", rejected.getFirst().message(),
                    Map.of("problems", rejected.stream().map(p -> Map.of("code", p.code(), "path", p.path())).toList()));
        }
        String before = pdf.getMap();
        if (pdf.getStatus() == PdfImportStatus.AWAITING_MAP) {
            pdf.startMapping();
        }
        PdfAnalysis.Result result = applier.apply(pdf, map, MapOrigin.USER, null);
        imports.save(pdf);
        auditTrail.record(AuditEntry.of("pdf_import.map_updated", RESOURCE, pdf.getId()).creditor(pdf.getCreditorId())
                .change("map", before == null ? null : analysis.mapAsObject(before), command.map())
                .meta("revision", pdf.getMapRevision()).meta("records", result.records().size())
                .meta("totalsMatch", result.totalsMatch()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PDF_IMPORT_MAP_EDITED)
                .creditor(pdf.getCreditorId()).portfolio(pdf.getPortfolioId()).reference(RESOURCE, pdf.getId())
                .attr("revision", pdf.getMapRevision()).attr("records", result.records().size())
                .attr("totalsMatch", result.totalsMatch()).attr("mapProblems", result.mapProblems().size()));
        metrics.mapEdited();
        return view(pdf, result);
    }

    @Override
    public PdfImportView requestNewMap(UUID id) {
        PdfImport pdf = loadForCommand(id);
        Guard.requireState(pdf.getStatus().editable(), "pdf_import.not_editable", "A importação não aceita alterações neste estado");
        Guard.require(pdf.hasTextLayer(), "pdf_import.visual_map_not_editable",
                "PDF sem camada de texto: envie o arquivo de novo para uma nova leitura visual");
        Guard.require(proposer.available(), "pdf_import.ai_unavailable", "A IA está desligada neste ambiente");
        pdf.startMapping();
        imports.save(pdf);
        jobQueue.enqueue(PdfMappingJobHandler.TYPE, new PdfMappingJobHandler.Payload(pdf.getId(), false), null,
                PdfMappingJobHandler.deduplicationKey(pdf.getId()));
        auditTrail.record(AuditEntry.of("pdf_import.new_map_requested", RESOURCE, pdf.getId())
                .creditor(pdf.getCreditorId()).meta("revision", pdf.getMapRevision()));
        return view(pdf, null);
    }

    @Override
    public RecordDecisionResult decideRecords(UUID id, RecordDecisionCommand command) {
        PdfImport pdf = loadForCommand(id);
        Guard.requireState(pdf.getStatus() == PdfImportStatus.AWAITING_VALIDATION, "pdf_import.not_editable",
                "A importação não aceita alterações neste estado");
        requireRevision(pdf, command.revision());
        RecordDecisions.Decision decision;
        try {
            decision = RecordDecisions.Decision.valueOf(command.decision() == null ? "" : command.decision().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("pdf_import.decision_invalid", "Decisão deve ser EXCLUDE, INCLUDE ou CONFIRM");
        }
        boolean byOrdinals = command.ordinals() != null && !command.ordinals().isEmpty();
        Guard.require(byOrdinals != (command.filter() != null), "pdf_import.records_required",
                "Escolha os títulos pelos números ou pelo filtro (um dos dois)");
        Guard.require(!byOrdinals || command.ordinals().size() <= BULK_MAX, "pdf_import.too_many_records",
                "Escolha até " + BULK_MAX + " títulos pelos números; para mais, use o filtro");
        String justification = command.justification() == null ? null : command.justification().strip();
        if (decision != RecordDecisions.Decision.INCLUDE) {
            Guard.require(justification != null && justification.length() >= JUSTIFICATION_MIN
                            && justification.length() <= PdfImport.TEXT_MAX,
                    "pdf_import.justification_required", "Informe a justificativa (de 5 a 500 caracteres)");
        }
        PdfAnalysis.Result result = analysis.analyze(pdf);
        RecordDecisions current = analysis.decisions(pdf);
        Map<Integer, CanonicalRecord> byOrdinal = result.records().stream()
                .collect(Collectors.toMap(CanonicalRecord::ordinal, r -> r));
        List<CanonicalRecord> targets = new ArrayList<>();
        if (byOrdinals) {
            for (Integer ordinal : new LinkedHashSet<>(command.ordinals())) {
                CanonicalRecord r = byOrdinal.get(ordinal);
                Guard.require(r != null, "pdf_import.record_not_found", "Título inexistente: " + ordinal);
                targets.add(r);
            }
        } else {
            targets = query(result, current, canSeeValues()).select(command.filter());
            if (command.expectedCount() != null && command.expectedCount() != targets.size()) {
                throw new ConflictException("pdf_import.selection_changed",
                        "O filtro tem agora %d título(s), e não %d: atualize a lista e selecione de novo"
                                .formatted(targets.size(), command.expectedCount()),
                        Map.of("records", targets.size()));
            }
            Guard.require(!targets.isEmpty(), "pdf_import.records_required", "Nenhum título no filtro");
        }
        boolean individual = byOrdinals && targets.size() == 1;
        RecordDecisionPolicy.Outcome outcome = decisionPolicy.decide(current, decision, targets.stream()
                .map(r -> new RecordDecisionPolicy.Target(r.ordinal(), r.origin() == RecordOrigin.VISUAL,
                        analysis.lowConfidence(r))).toList(), individual);
        UUID operationId = Ids.newId();
        PdfAnalysis.Result updated = result;
        if (!outcome.applied().isEmpty()) {
            pdf.updateDecisions(analysis.toJson(outcome.decisions()));
            // recálculo determinístico: os totais de controle passam a conferir só os títulos que vão para o lote
            updated = analysis.analyze(pdf, outcome.decisions());
            pdf.updateSummary(analysis.summaryJson(updated, outcome.decisions()));
            imports.save(pdf);
            for (Integer ordinal : outcome.applied()) {
                auditTrail.record(AuditEntry.of("pdf_import.record_decided", RESOURCE, pdf.getId())
                        .creditor(pdf.getCreditorId()).reason(justification).meta("decision", decision.name())
                        .meta("record", ordinal)
                        .meta("titleId", byOrdinal.get(ordinal).values().get(CanonicalField.TITLE_EXTERNAL_ID))
                        .meta("operationId", operationId).meta("bulk", !individual)
                        .meta("revision", pdf.getMapRevision()));
            }
        }
        metrics.recordsDecided(decision.name(), !individual, outcome.applied().size(), outcome.skippedCount());
        LOG.atInfo().addKeyValue("pdfImportId", pdf.getId()).addKeyValue("operationId", operationId)
                .addKeyValue("decision", decision.name()).addKeyValue("bulk", !individual)
                .addKeyValue("requested", targets.size()).addKeyValue("applied", outcome.applied().size())
                .addKeyValue("skipped", outcome.skippedCount()).log("Decisão sobre títulos da importação PDF");
        return new RecordDecisionResult(view(pdf, updated), operationId, decision.name(), !individual, targets.size(),
                outcome.applied().size(), outcome.skipped().stream()
                .map(g -> new SkippedRecords(g.code(), g.message(), g.ordinals())).toList());
    }

    // ================================================================== aprovação

    @Override
    public PdfImportView approve(UUID id, ApproveCommand command) {
        PdfImport pdf = loadForCommand(id);
        Guard.requireState(pdf.getStatus() == PdfImportStatus.AWAITING_VALIDATION, "pdf_import.not_awaiting_validation",
                "Só uma importação aguardando validação pode ser aprovada");
        requireRevision(pdf, command.revision());
        PdfAnalysis.Result result = analysis.analyze(pdf);
        RecordDecisions decisions = analysis.decisions(pdf);
        List<String> blockers = blockers(pdf, result, decisions);
        if (!blockers.isEmpty()) {
            throw new BusinessRuleException("pdf_import.approval_blocked", blockers.getFirst(), Map.of("blockers", blockers));
        }
        boolean visual = pdf.getMapOrigin() == MapOrigin.VISUAL;
        Guard.require(!visual || command.visualReadingConfirmed(), "pdf_import.visual_confirmation_required",
                "Confirme que conferiu os valores lidos visualmente com as imagens das páginas");
        List<CanonicalRecord> toImport = result.records().stream().filter(r -> !decisions.isExcluded(r.ordinal())).toList();
        byte[] csv = canonicalCsv(toImport);
        String csvName = csvName(pdf);
        ReviewView review = reviews.start(new StartReviewCommand(pdf.getPortfolioId(), null, csvName, "text/csv",
                csv.length, () -> new ByteArrayInputStream(csv)));
        pdf.approve(currentActor.actorId(), clock.instant(), review.id(), result.totalsMatch(), command.totalsJustification());
        pdf.updateSummary(analysis.summaryJson(result, decisions));
        imports.save(pdf);
        PdfAnalysis.Summary summary = analysis.summary(result, decisions);
        long userRevisions = revisions.findByPdfImportIdOrderByRevisionAsc(pdf.getId()).stream()
                .filter(r -> r.getOrigin() == MapOrigin.USER).count();
        auditTrail.record(AuditEntry.of("pdf_import.approved", RESOURCE, pdf.getId()).creditor(pdf.getCreditorId())
                .change("status", PdfImportStatus.AWAITING_VALIDATION.name(), PdfImportStatus.APPROVED.name())
                .created("reviewId", review.id()).created("mapRevision", pdf.getMapRevision())
                .created("mapOrigin", pdf.getMapOrigin().name()).created("records", toImport.size())
                .created("excluded", decisions.excluded().size()).created("totalsMatch", result.totalsMatch())
                .reason(pdf.getTotalsJustification()).meta("fingerprint", pdf.getFingerprint())
                .meta("templateId", pdf.getTemplateId()).meta("visualReadingConfirmed", command.visualReadingConfirmed()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PDF_IMPORT_MAP_APPROVED)
                .creditor(pdf.getCreditorId()).portfolio(pdf.getPortfolioId()).reference(RESOURCE, pdf.getId())
                .attr("origin", pdf.getMapOrigin().name()).attr("revision", pdf.getMapRevision())
                .attr("userEdits", userRevisions).attr("templateId", pdf.getTemplateId())
                .attr("totalsMatch", result.totalsMatch()).attr("totalsJustified", !result.totalsMatch()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PDF_IMPORT_IMPORTED)
                .creditor(pdf.getCreditorId()).portfolio(pdf.getPortfolioId()).reference(RESOURCE, pdf.getId())
                .amount(summary.principalTotal()).attr("reviewId", review.id()).attr("records", toImport.size())
                .attr("excluded", decisions.excluded().size()).attr("debtors", summary.debtors())
                .attr("balanceTotal", summary.balanceTotal()).attr("pages", pdf.getPageCount())
                .attr("origin", pdf.getMapOrigin().name()).attr("aiCost", pdf.getAiCost())
                .attr("aiCurrency", pdf.getAiCurrency()).attr("inputTokens", pdf.getAiInputTokens())
                .attr("outputTokens", pdf.getAiOutputTokens())
                .durationMs(java.time.Duration.between(pdf.getCreatedAt(), pdf.getApprovedAt()).toMillis()));
        metrics.approved(pdf, result.totalsMatch(), toImport.size());
        LOG.atInfo().addKeyValue("pdfImportId", pdf.getId()).addKeyValue("reviewId", review.id())
                .addKeyValue("records", toImport.size()).log("Importação PDF aprovada; conferência aberta");
        return view(pdf, result);
    }

    /** Motivos que impedem a aprovação agora (a divergência de totais pede justificativa, não bloqueia aqui). */
    private List<String> blockers(PdfImport pdf, PdfAnalysis.Result result, RecordDecisions decisions) {
        List<String> out = new ArrayList<>();
        if (pdf.getStatus() != PdfImportStatus.AWAITING_VALIDATION) {
            return out;
        }
        if (result == null) {
            out.add("O arquivo não está mais disponível para análise");
            return out;
        }
        result.hardBlocking().stream().map(Finding::message).distinct().forEach(out::add);
        long toImport = result.records().stream().filter(r -> !decisions.isExcluded(r.ordinal())).count();
        if (toImport == 0 && !result.records().isEmpty()) {
            out.add("Todos os títulos foram excluídos");
        }
        long unconfirmed = result.records().stream().filter(r -> !decisions.isExcluded(r.ordinal())
                && analysis.lowConfidence(r) && !decisions.isConfirmed(r.ordinal())).count();
        if (unconfirmed > 0) {
            out.add(unconfirmed + " título(s) lidos visualmente com confiança baixa: confirme ou exclua cada um");
        }
        return out;
    }

    private byte[] canonicalCsv(List<CanonicalRecord> records) {
        Set<CanonicalField> used = java.util.EnumSet.noneOf(CanonicalField.class);
        records.forEach(r -> used.addAll(r.values().keySet()));
        List<String> columns = Arrays.stream(CanonicalField.values()).filter(used::contains).map(CanonicalField::column).toList();
        List<Map<String, String>> rows = new ArrayList<>();
        for (CanonicalRecord r : records) {
            Map<String, String> row = new LinkedHashMap<>();
            r.values().forEach((f, v) -> row.put(f.column(), v));
            rows.add(row);
        }
        return fileWriter.csv(columns, rows);
    }

    private static String csvName(PdfImport pdf) {
        String base = pdf.getFileName().replaceAll("(?i)\\.pdf$", "");
        String name = base + "-pdf-r" + pdf.getMapRevision() + ".csv";
        return name.length() <= 200 ? name : name.substring(name.length() - 200);
    }

    // ================================================================== mapas salvos

    @Override
    public PdfTemplateView saveTemplate(UUID id, SaveTemplateCommand command) {
        PdfImport pdf = loadForCommand(id);
        Guard.requireState(pdf.getStatus() == PdfImportStatus.AWAITING_VALIDATION || pdf.getStatus() == PdfImportStatus.APPROVED,
                "pdf_template.import_not_validated", "Salve o mapa de uma importação aguardando validação ou aprovada");
        Guard.require(pdf.getMapOrigin() != MapOrigin.VISUAL && pdf.hasTextLayer(), "pdf_template.visual_not_reusable",
                "Mapa de leitura visual não é reaplicável: só PDFs com camada de texto geram mapa salvo");
        requireRevision(pdf, command.revision());
        // o mapa em si (sem as exclusões do usuário): os totais do documento conferem com tudo o que o mapa encontra
        PdfAnalysis.Result result = analysis.analyzeMap(pdf);
        Guard.require(result.hardBlocking().isEmpty() && result.totalsMatch(), "pdf_template.map_not_validated",
                "Só um mapa sem bloqueios e com os totais do documento conferindo pode ser salvo para o credor");
        ExtractionMap map = result.map();
        String fingerprint = LayoutFingerprint.of(map);
        int version = 1;
        var latest = templates.findFirstByCreditorIdAndFingerprintOrderByTemplateVersionDesc(pdf.getCreditorId(), fingerprint);
        if (latest.isPresent()) {
            version = latest.get().getTemplateVersion() + 1;
            if (latest.get().isActive()) {
                latest.get().deactivate(currentActor.actorId(), clock.instant());
                templates.save(latest.get());
            }
        }
        PdfLayoutTemplate template = templates.save(new PdfLayoutTemplate(pdf.getCreditorId(), command.name(), fingerprint,
                version, analysis.toJson(map), pdf.getId()));
        auditTrail.record(AuditEntry.of("pdf_layout_template.saved", TEMPLATE_RESOURCE, template.getId())
                .creditor(pdf.getCreditorId()).created("name", template.getName()).created("version", version)
                .created("fingerprint", fingerprint).meta("pdfImportId", pdf.getId())
                .meta("replaced", latest.map(PdfLayoutTemplate::getId).orElse(null)));
        metrics.templateSaved();
        return views.template(template);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PdfTemplateView> templates(UUID creditorId, boolean includeInactive) {
        Guard.require(creditorId != null, "pdf_template.creditor_required", "Informe o credor");
        accessScope.requireVisible("creditor", creditorId, creditorId);
        return templates.findByCreditorIdOrderByNameAscTemplateVersionDesc(creditorId).stream()
                .filter(t -> includeInactive || t.isActive()).map(views::template).toList();
    }

    @Override
    public PdfTemplateView deactivateTemplate(UUID templateId) {
        PdfLayoutTemplate template = templates.findById(templateId)
                .orElseThrow(() -> ResourceNotFoundException.of(TEMPLATE_RESOURCE, templateId));
        accessScope.requireVisible(TEMPLATE_RESOURCE, templateId, template.getCreditorId());
        accessScope.requireCreditor(template.getCreditorId());
        template.deactivate(currentActor.actorId(), clock.instant());
        templates.save(template);
        auditTrail.record(AuditEntry.of("pdf_layout_template.deactivated", TEMPLATE_RESOURCE, templateId)
                .creditor(template.getCreditorId()).change("active", true, false));
        return views.template(template);
    }

    // ================================================================== descarte

    @Override
    public PdfImportView discard(UUID id, String reason) {
        PdfImport pdf = loadForCommand(id);
        PdfImportStatus before = pdf.getStatus();
        pdf.discard(currentActor.actorId(), clock.instant(), reason);
        imports.save(pdf);
        jobQueue.cancelPending(PdfMappingJobHandler.TYPE, PdfMappingJobHandler.deduplicationKey(pdf.getId()));
        analysis.forget(pdf);
        auditTrail.record(AuditEntry.of("pdf_import.discarded", RESOURCE, pdf.getId()).creditor(pdf.getCreditorId())
                .change("status", before.name(), PdfImportStatus.DISCARDED.name()).reason(pdf.getDiscardReason()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PDF_IMPORT_DISCARDED)
                .creditor(pdf.getCreditorId()).portfolio(pdf.getPortfolioId()).reference(RESOURCE, pdf.getId())
                .attr("previousStatus", before.name()).attr("origin", pdf.getMapOrigin() == null ? null : pdf.getMapOrigin().name())
                .attr("revision", pdf.getMapRevision()));
        metrics.discarded();
        return view(pdf, null);
    }

    // ================================================================== apoio

    private PdfImport loadVisible(UUID id) {
        PdfImport pdf = imports.findById(id).orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, id));
        accessScope.requireVisible(RESOURCE, id, pdf.getCreditorId());
        return pdf;
    }

    private PdfImport loadForCommand(UUID id) {
        PdfImport pdf = imports.findByIdForUpdate(id).orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, id));
        accessScope.requireVisible(RESOURCE, id, pdf.getCreditorId());
        accessScope.requireCreditor(pdf.getCreditorId());
        return pdf;
    }

    private static void requireRevision(PdfImport pdf, int revision) {
        if (revision != pdf.getMapRevision()) {
            throw new ConflictException("pdf_import.revision_changed",
                    "O mapa mudou desde que a tela foi aberta (revisão %d); atualize e confira de novo".formatted(pdf.getMapRevision()),
                    Map.of("revision", pdf.getMapRevision()));
        }
    }
}
