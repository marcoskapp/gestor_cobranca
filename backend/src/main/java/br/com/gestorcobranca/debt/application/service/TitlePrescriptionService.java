package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.DebtProperties;
import br.com.gestorcobranca.debt.application.port.in.PrescriptionInterruptionPort;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases;
import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.InterruptionOrigin;
import br.com.gestorcobranca.debt.domain.model.InterruptionType;
import br.com.gestorcobranca.debt.domain.model.OriginContract;
import br.com.gestorcobranca.debt.domain.model.PrescriptionCalculation;
import br.com.gestorcobranca.debt.domain.model.PrescriptionClaim;
import br.com.gestorcobranca.debt.domain.model.PrescriptionInterruption;
import br.com.gestorcobranca.debt.domain.model.PrescriptionRule;
import br.com.gestorcobranca.debt.domain.model.TitlePrescription;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.debt.domain.repository.OriginContractRepository;
import br.com.gestorcobranca.debt.domain.repository.PrescriptionInterruptionRepository;
import br.com.gestorcobranca.debt.domain.repository.TitlePrescriptionRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.shared.storage.FileStorage.StoredFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.Clock;
import java.util.UUID;

/**
 * Consulta da prescrição e registro de interrupções, manual (web) ou pelo módulo legal
 * ({@link PrescriptionInterruptionPort}). O registro trava o título (interrupções concorrentes
 * do mesmo título são serializadas) e recalcula a prescrição do contrato na mesma transação.
 */
@Service
@Transactional
class TitlePrescriptionService implements TitlePrescriptionUseCases, PrescriptionInterruptionPort {

    static final String ENTITY = "prescription_interruption";
    private static final String TITLE = "debt_title";
    private static final Set<String> EVIDENCE_TYPES = Set.of("application/pdf", "image/png", "image/jpeg");

    private final DebtTitleRepository titles;
    private final OriginContractRepository contracts;
    private final PrescriptionInterruptionRepository interruptions;
    private final TitlePrescriptionRepository prescriptions;
    private final PrescriptionRecalculator recalculator;
    private final FileStorage fileStorage;
    private final EvidenceFileCleanup fileCleanup;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final AccessLogger accessLogger;
    private final DebtMetrics metrics;
    private final DebtProperties properties;
    private final Clock clock;

    TitlePrescriptionService(DebtTitleRepository titles, OriginContractRepository contracts,
                             PrescriptionInterruptionRepository interruptions, TitlePrescriptionRepository prescriptions,
                             PrescriptionRecalculator recalculator, FileStorage fileStorage,
                             EvidenceFileCleanup fileCleanup, AccessScope accessScope, CurrentActor currentActor,
                             AuditTrail auditTrail, BusinessEventLogger businessEvents, AccessLogger accessLogger,
                             DebtMetrics metrics, DebtProperties properties, Clock clock) {
        this.titles = titles;
        this.contracts = contracts;
        this.interruptions = interruptions;
        this.prescriptions = prescriptions;
        this.recalculator = recalculator;
        this.fileStorage = fileStorage;
        this.fileCleanup = fileCleanup;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.accessLogger = accessLogger;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- consulta

    @Override
    @Transactional(readOnly = true)
    public PrescriptionView get(UUID titleId) {
        DebtTitle title = titles.findById(titleId).orElseThrow(() -> ResourceNotFoundException.of(TITLE, titleId));
        accessScope.requireVisible(TITLE, titleId, title.getCreditorId());
        return view(title);
    }

    private PrescriptionView view(DebtTitle title) {
        LocalDate today = DebtViews.today(clock);
        Optional<OriginContract> contract = title.getOriginContractId() == null ? Optional.empty()
                : contracts.findById(title.getOriginContractId());
        List<PrescriptionCalculation> calculations = recalculator.calculate(title);
        Map<UUID, PrescriptionRule> rules = recalculator.rulesOf(calculations);
        Map<PrescriptionClaim, TitlePrescription> stored = new EnumMap<>(PrescriptionClaim.class);
        prescriptions.findByTitleId(title.getId()).forEach(p -> stored.put(p.getClaim(), p));

        List<ClaimView> claims = new ArrayList<>();
        Map<UUID, List<String>> effectiveFor = new java.util.HashMap<>();
        for (PrescriptionCalculation c : calculations) {
            PrescriptionRule rule = rules.get(c.ruleId());
            long days = ChronoUnit.DAYS.between(today, c.estimatedDate());
            TitlePrescription row = stored.get(c.claim());
            claims.add(new ClaimView(c.claim().name(), days > 0 ? "RUNNING" : "PRESCRIBED", c.ruleId(),
                    rule != null && rule.isValidated(), rule == null ? null : rule.getLegalBasis(), c.baseDate(),
                    c.countingStart(), c.termMonths(), c.estimatedDate(), days,
                    row == null ? null : row.getLastAlertedFor()));
            c.effectiveInterruptions().forEach(id -> effectiveFor.computeIfAbsent(id, k -> new ArrayList<>())
                    .add(c.claim().name()));
        }
        claims.sort(java.util.Comparator.comparing(ClaimView::claim));
        List<InterruptionView> history = recalculator.interruptionsOf(title.getId()).stream()
                .map(i -> interruptionView(i, effectiveFor.getOrDefault(i.getId(), List.of()))).toList();
        return new PrescriptionView(title.getId(), title.getOriginContractId(),
                contract.map(c -> c.getInstrumentType().name()).orElse(null),
                contract.map(OriginContract::isExecutiveTitle).orElse(false), claims, history);
    }

    private static InterruptionView interruptionView(PrescriptionInterruption i, List<String> effectiveFor) {
        EvidenceView evidence = i.evidence()
                .map(e -> new EvidenceView(e.name(), e.contentType(), e.size(), e.sha256())).orElse(null);
        return new InterruptionView(i.getId(), i.getType().name(), i.getInterruptionDate(), i.getReference(),
                i.getNotes(), i.getOrigin().name(), i.getOriginReferenceId(), List.copyOf(effectiveFor), evidence,
                i.getCreatedAt(), i.getCreatedBy());
    }

    // ---------------------------------------------------------------- registro manual

    @Override
    public PrescriptionView registerInterruption(UUID titleId, RegisterInterruptionCommand command,
                                                 EvidenceUpload upload) {
        DebtTitle title = lockForCommand(titleId);
        InterruptionType type = DebtViews.parseEnum(InterruptionType.class, command.type(),
                "prescription_interruption.type_invalid", "Tipo de interrupção");
        requireValidDate(command.date());
        if (interruptions.findByTitleIdAndTypeAndInterruptionDate(titleId, type, command.date()).isPresent()) {
            throw new ConflictException("prescription_interruption.duplicate",
                    "Interrupção deste tipo já registrada nesta data para o título");
        }
        PrescriptionInterruption.Evidence evidence = upload == null ? null : store(upload);
        record(title, new PrescriptionInterruption(titleId, type, command.date(), command.reference(), command.notes(),
                InterruptionOrigin.MANUAL, null, evidence));
        return view(title);
    }

    private PrescriptionInterruption.Evidence store(EvidenceUpload upload) {
        Guard.require(upload.content() != null && upload.size() > 0, "prescription_interruption.evidence_empty",
                "Arquivo de evidência vazio");
        Guard.require(upload.size() <= properties.evidence().maxSizeBytes(), "prescription_interruption.evidence_too_large",
                "Arquivo de evidência excede o tamanho máximo");
        String contentType = upload.contentType() == null ? ""
                : upload.contentType().toLowerCase(Locale.ROOT).split(";")[0].trim();
        Guard.require(EVIDENCE_TYPES.contains(contentType), "prescription_interruption.evidence_type_invalid",
                "Evidência deve ser PDF, PNG ou JPEG");
        StoredFile stored = fileStorage.store(FileCategory.EVIDENCE, upload.fileName(), contentType, upload.content(),
                upload.size());
        fileCleanup.deleteOnRollback(stored.key());
        return new PrescriptionInterruption.Evidence(stored.key(), stored.originalName(), contentType, stored.size(),
                stored.sha256());
    }

    // ---------------------------------------------------------------- porta do módulo legal

    @Override
    public InterruptionResult register(LegalInterruption request) {
        Guard.require(request.titleId() != null, "prescription_interruption.title_required", "Título obrigatório");
        DebtTitle title = lockForCommand(request.titleId());
        InterruptionType type = DebtViews.parseEnum(InterruptionType.class, request.type(),
                "prescription_interruption.type_invalid", "Tipo de interrupção");
        requireValidDate(request.date());
        Optional<PrescriptionInterruption> existing =
                interruptions.findByTitleIdAndTypeAndInterruptionDate(title.getId(), type, request.date());
        if (existing.isPresent()) {
            return result(existing.get(), false);
        }
        PrescriptionInterruption.Evidence evidence = null;
        if (request.evidence() != null) {
            StoredEvidence e = request.evidence();
            requireTenantKey(e.key());
            evidence = new PrescriptionInterruption.Evidence(e.key(), e.fileName(), e.contentType(), e.size(), e.sha256());
        }
        PrescriptionInterruption interruption = record(title, new PrescriptionInterruption(title.getId(), type,
                request.date(), request.reference(), request.notes(), InterruptionOrigin.LEGAL,
                request.originReferenceId(), evidence));
        return result(interruption, true);
    }

    private InterruptionResult result(PrescriptionInterruption interruption, boolean registered) {
        Map<PrescriptionClaim, LocalDate> dates = new EnumMap<>(PrescriptionClaim.class);
        prescriptions.findByTitleId(interruption.getTitleId()).forEach(p -> dates.put(p.getClaim(), p.getEstimatedDate()));
        return new InterruptionResult(interruption.getId(), interruption.getTitleId(), registered,
                dates.get(PrescriptionClaim.EXECUTION), dates.get(PrescriptionClaim.COLLECTION));
    }

    /** A evidência do módulo chamador deve estar no armazenamento do tenant corrente. */
    private void requireTenantKey(String key) {
        String prefix = "tenants/" + currentActor.tenantId() + "/";
        Guard.require(key != null && key.startsWith(prefix) && !key.contains(".."),
                "prescription_interruption.evidence_key_invalid", "Evidência fora do armazenamento da assessoria");
    }

    // ---------------------------------------------------------------- gravação comum

    private PrescriptionInterruption record(DebtTitle title, PrescriptionInterruption interruption) {
        interruptions.save(interruption);
        Map<PrescriptionClaim, LocalDate> before = estimatedDates(title.getId());
        recalculator.refreshFor(title, PrescriptionRecalculator.TRIGGER_INTERRUPTION);
        Map<PrescriptionClaim, LocalDate> after = estimatedDates(title.getId());
        AuditEntry audit = AuditEntry.of("prescription_interruption.registered", ENTITY, interruption.getId())
                .creditor(title.getCreditorId())
                .created("titleId", title.getId())
                .created("type", interruption.getType())
                .created("date", interruption.getInterruptionDate())
                .created("reference", interruption.getReference())
                .created("origin", interruption.getOrigin())
                .created("originReferenceId", interruption.getOriginReferenceId());
        interruption.evidence().ifPresent(e -> audit.created("evidenceFileName", e.name())
                .created("evidenceSha256", e.sha256()));
        for (PrescriptionClaim claim : PrescriptionClaim.values()) {
            if (before.get(claim) != null || after.get(claim) != null) {
                audit.change(claim.name().toLowerCase(Locale.ROOT) + "EstimatedDate", before.get(claim), after.get(claim));
            }
        }
        auditTrail.record(audit);
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.TITLE_PRESCRIPTION_INTERRUPTED)
                .creditor(title.getCreditorId()).portfolio(title.getPortfolioId()).debtor(title.getDebtorId())
                .reference(TITLE, title.getId()).amount(title.getOpenBalance())
                .attr("type", interruption.getType()).attr("origin", interruption.getOrigin())
                .attr("hasEvidence", interruption.evidence().isPresent())
                .attr("executionEstimatedDate", text(after.get(PrescriptionClaim.EXECUTION)))
                .attr("collectionEstimatedDate", text(after.get(PrescriptionClaim.COLLECTION)))
                .attr("extended", extended(before, after)));
        metrics.interruptionRegistered(interruption.getType(), interruption.getOrigin());
        return interruption;
    }

    private static boolean extended(Map<PrescriptionClaim, LocalDate> before, Map<PrescriptionClaim, LocalDate> after) {
        return after.entrySet().stream().anyMatch(e -> before.get(e.getKey()) != null
                && e.getValue().isAfter(before.get(e.getKey())));
    }

    private static String text(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private Map<PrescriptionClaim, LocalDate> estimatedDates(UUID titleId) {
        Map<PrescriptionClaim, LocalDate> dates = new EnumMap<>(PrescriptionClaim.class);
        prescriptions.findByTitleId(titleId).forEach(p -> dates.put(p.getClaim(), p.getEstimatedDate()));
        return dates;
    }

    private void requireValidDate(LocalDate date) {
        Guard.require(date != null, "prescription_interruption.date_required", "Data da interrupção obrigatória");
        Guard.require(!date.isAfter(DebtViews.today(clock)), "prescription_interruption.date_future",
                "Data da interrupção não pode ser futura");
    }

    // ---------------------------------------------------------------- evidência

    @Override
    @Transactional(readOnly = true)
    public EvidenceDownload evidence(UUID titleId, UUID interruptionId) {
        DebtTitle title = titles.findById(titleId).orElseThrow(() -> ResourceNotFoundException.of(TITLE, titleId));
        accessScope.requireVisible(TITLE, titleId, title.getCreditorId());
        PrescriptionInterruption interruption = interruptions.findById(interruptionId)
                .filter(i -> i.getTitleId().equals(titleId))
                .orElseThrow(() -> ResourceNotFoundException.of(ENTITY, interruptionId));
        PrescriptionInterruption.Evidence evidence = interruption.evidence()
                .orElseThrow(() -> new ResourceNotFoundException("prescription_interruption_evidence", interruptionId));
        accessLogger.log(AccessEvent.of(AccessEventType.FILE_DOWNLOADED, currentActor.get(),
                Map.of("resource", ENTITY, "resourceId", interruptionId.toString(),
                        "category", FileCategory.EVIDENCE.name())));
        return new EvidenceDownload(evidence.name(), evidence.contentType(), evidence.size(),
                fileStorage.open(evidence.key()));
    }

    private DebtTitle lockForCommand(UUID titleId) {
        DebtTitle title = titles.findByIdForUpdate(titleId).orElseThrow(() -> ResourceNotFoundException.of(TITLE, titleId));
        accessScope.requireVisible(TITLE, titleId, title.getCreditorId());
        accessScope.requireCreditor(title.getCreditorId());
        return title;
    }
}
