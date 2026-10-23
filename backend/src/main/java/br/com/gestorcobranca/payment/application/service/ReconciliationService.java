package br.com.gestorcobranca.payment.application.service;

import br.com.gestorcobranca.payment.application.PaymentProperties;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases;
import br.com.gestorcobranca.payment.application.port.out.StatementParser;
import br.com.gestorcobranca.payment.domain.model.Divergence;
import br.com.gestorcobranca.payment.domain.model.DivergenceResolution;
import br.com.gestorcobranca.payment.domain.model.DivergenceStatus;
import br.com.gestorcobranca.payment.domain.model.DivergenceType;
import br.com.gestorcobranca.payment.domain.model.Payment;
import br.com.gestorcobranca.payment.domain.model.ReconciliationMode;
import br.com.gestorcobranca.payment.domain.model.StatementFormat;
import br.com.gestorcobranca.payment.domain.model.StatementImport;
import br.com.gestorcobranca.payment.domain.model.BankStatementLine;
import br.com.gestorcobranca.payment.domain.repository.DivergenceRepository;
import br.com.gestorcobranca.payment.domain.repository.PaymentRepository;
import br.com.gestorcobranca.payment.domain.repository.BankStatementRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.jobs.JobQueue;
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

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
class ReconciliationService implements ReconciliationUseCases {

    static final String RESOURCE = "statement_import";
    private static final String DIVERGENCE = "payment_divergence";

    private final BankStatementRepository statements;
    private final DivergenceRepository divergences;
    private final PaymentRepository payments;
    private final Map<StatementFormat, StatementParser> parsers;
    private final PaymentRecorder recorder;
    private final FileStorage fileStorage;
    private final FileCleanup cleanup;
    private final JobQueue jobs;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final PaymentProperties properties;
    private final Clock clock;

    ReconciliationService(BankStatementRepository statements, DivergenceRepository divergences, PaymentRepository payments,
                          List<StatementParser> parsers, PaymentRecorder recorder, FileStorage fileStorage,
                          FileCleanup cleanup, JobQueue jobs, AccessScope accessScope, CurrentActor currentActor,
                          AuditTrail auditTrail, PaymentProperties properties, Clock clock) {
        this.statements = statements;
        this.divergences = divergences;
        this.payments = payments;
        this.parsers = parsers.stream().collect(Collectors.toUnmodifiableMap(StatementParser::format, Function.identity()));
        this.recorder = recorder;
        this.fileStorage = fileStorage;
        this.cleanup = cleanup;
        this.jobs = jobs;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.properties = properties;
        this.clock = clock;
    }

    StatementParser parser(StatementFormat format) {
        StatementParser parser = parsers.get(format);
        if (parser == null) {
            throw new BusinessRuleException("statement.format_unavailable",
                    "Leitor do formato %s ainda não disponível (use CSV_GENERIC)".formatted(format));
        }
        return parser;
    }

    @Override
    public StatementImportView importStatement(StatementUpload upload) {
        accessScope.requirePermission(Permission.RECONCILIATION_MANAGE);
        Guard.require(upload != null && upload.content() != null && upload.size() > 0, "statement.file_required",
                "Arquivo obrigatório");
        Guard.require(upload.size() <= properties.statement().maxSizeBytes(), "statement.file_too_large",
                "Arquivo excede o tamanho máximo");
        StatementFormat format = PaymentEnums.parse(StatementFormat.class, upload.format(), "statement.format_invalid",
                "Formato");
        parser(format);
        requireScope(upload.creditorId());
        StoredFile stored = fileStorage.store(FileCategory.PAYMENT_RETURN, upload.fileName(), "text/csv", upload.content(),
                upload.size());
        cleanup.deleteOnRollback(stored.key());
        if (statements.existsImportBySha256(stored.sha256())) {
            throw new ConflictException("statement.already_imported", "Este arquivo já foi importado");
        }
        StatementImport source = statements.saveAndFlush(new StatementImport(format, upload.creditorId(), stored.key(),
                stored.originalName(), stored.size(), stored.sha256(), currentActor.actorId()));
        StatementImportJob.enqueue(jobs, source.getId());
        auditTrail.record(AuditEntry.of("statement_import.received", RESOURCE, source.getId())
                .creditor(source.getCreditorId()).created("format", format).created("fileSha256", stored.sha256())
                .created("fileSize", stored.size()));
        return PaymentViews.statementImport(source);
    }

    @Override
    @Transactional(readOnly = true)
    public StatementImportView getImport(UUID importId) {
        accessScope.requirePermission(Permission.RECONCILIATION_MANAGE);
        return PaymentViews.statementImport(loadImport(importId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<StatementImportView> imports(PageQuery page) {
        accessScope.requirePermission(Permission.RECONCILIATION_MANAGE);
        accessScope.requireAllCreditors(RESOURCE);
        return PageResponse.of(statements.imports(page.toPageable(Sort.by(Sort.Direction.DESC, "createdAt"))),
                PaymentViews::statementImport);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<StatementLineView> lines(UUID importId, PageQuery page) {
        accessScope.requirePermission(Permission.RECONCILIATION_MANAGE);
        StatementImport source = loadImport(importId);
        return PageResponse.of(statements.lines(source.getId(), page.toPageable(Sort.by("lineNumber"))), PaymentViews::line);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DivergenceView> divergences(DivergenceFilter filter, PageQuery page) {
        accessScope.requirePermission(Permission.RECONCILIATION_MANAGE);
        DivergenceFilter f = filter == null ? new DivergenceFilter(null, null) : filter;
        Set<DivergenceStatus> statuses = PaymentEnums.parseSet(DivergenceStatus.class,
                f.status() == null ? List.of() : List.of(f.status()), "divergence.status_invalid", "Situação");
        DivergenceType type = PaymentEnums.parseOptional(DivergenceType.class, f.type(), "divergence.type_invalid", "Tipo");
        Set<UUID> creditors = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
        return PageResponse.of(divergences.search(creditors, statuses, type,
                page.toPageable(Sort.by("createdAt").and(Sort.by("id")))), this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public DivergenceView getDivergence(UUID divergenceId) {
        accessScope.requirePermission(Permission.RECONCILIATION_MANAGE);
        return view(loadDivergence(divergenceId));
    }

    @Override
    public DivergenceView resolve(UUID divergenceId, ResolveCommand command) {
        accessScope.requirePermission(Permission.RECONCILIATION_MANAGE);
        Guard.require(command != null, "divergence.resolution_required", "Resolução obrigatória");
        Divergence d = divergences.findByIdForUpdate(divergenceId)
                .orElseThrow(() -> new ResourceNotFoundException(DIVERGENCE, divergenceId));
        requireVisible(d);
        requireScope(d.getCreditorId());
        DivergenceResolution resolution = PaymentEnums.parse(DivergenceResolution.class, command.resolution(),
                "divergence.resolution_invalid", "Resolução");
        Instant now = Instant.now(clock);
        UUID matched = null;
        if (resolution == DivergenceResolution.MATCHED_PAYMENT) {
            Guard.require(command.paymentId() != null, "divergence.payment_required", "Informe o pagamento correspondente");
            Payment p = payments.findByIdForUpdate(command.paymentId())
                    .orElseThrow(() -> new ResourceNotFoundException("payment", command.paymentId()));
            accessScope.requireVisible("payment", command.paymentId(), p.getCreditorId());
            accessScope.requireCreditor(p.getCreditorId());
            if (d.getCreditorId() != null && !d.getCreditorId().equals(p.getCreditorId())) {
                throw new BusinessRuleException("divergence.creditor_mismatch",
                        "Pagamento de outro credor que o da divergência");
            }
            if (p.reconcile(ReconciliationMode.MANUAL, currentActor.actorId(), d.getStatementLineId(), now)) {
                payments.save(p);
                recorder.reconciled(p, ReconciliationMode.MANUAL, null);
            }
            if (d.getStatementLineId() != null) {
                statements.findLine(d.getStatementLineId()).ifPresent(line -> {
                    line.reconciledWith(p.getId());
                    statements.save(line);
                });
            }
            matched = p.getId();
        }
        d.resolve(resolution, matched, command.note(), currentActor.actorId(), now);
        divergences.save(d);
        recorder.divergenceResolved(d);
        return view(d);
    }

    private StatementImport loadImport(UUID importId) {
        StatementImport source = statements.findImport(importId)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, importId));
        if (source.getCreditorId() == null) {
            if (!accessScope.hasAllCreditors()) {
                throw new ResourceNotFoundException(RESOURCE, importId);
            }
        } else {
            accessScope.requireVisible(RESOURCE, importId, source.getCreditorId());
        }
        return source;
    }

    private Divergence loadDivergence(UUID id) {
        Divergence d = divergences.findById(id).orElseThrow(() -> new ResourceNotFoundException(DIVERGENCE, id));
        requireVisible(d);
        return d;
    }

    private void requireVisible(Divergence d) {
        if (d.getCreditorId() == null) {
            if (!accessScope.hasAllCreditors()) {
                throw new ResourceNotFoundException(DIVERGENCE, d.getId());
            }
        } else {
            accessScope.requireVisible(DIVERGENCE, d.getId(), d.getCreditorId());
        }
    }

    private void requireScope(UUID creditorId) {
        if (creditorId == null) {
            accessScope.requireAllCreditors(RESOURCE);
        } else {
            accessScope.requireCreditor(creditorId);
        }
    }

    private DivergenceView view(Divergence d) {
        Optional<BankStatementLine> line = d.getStatementLineId() == null ? Optional.empty()
                : statements.findLine(d.getStatementLineId());
        return PaymentViews.divergence(d, line);
    }
}
