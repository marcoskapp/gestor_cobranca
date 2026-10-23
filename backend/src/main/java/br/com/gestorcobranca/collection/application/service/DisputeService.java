package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.CollectionProperties;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases;
import br.com.gestorcobranca.collection.domain.model.AssignmentMode;
import br.com.gestorcobranca.collection.domain.model.CaseStatus;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DisputeEvidence;
import br.com.gestorcobranca.collection.domain.model.DisputeOrigin;
import br.com.gestorcobranca.collection.domain.model.DisputeOutcome;
import br.com.gestorcobranca.collection.domain.model.DisputeStatus;
import br.com.gestorcobranca.collection.domain.model.DisputeType;
import br.com.gestorcobranca.collection.domain.repository.CollectionCaseRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeEvidenceRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeRepository;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.collection.DisputeStatusChanged;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
class DisputeService implements DisputeUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(DisputeService.class);
    /** Tipos de arquivo aceitos como evidência (documentos, imagens, áudio, texto, e-mail). */
    private static final Set<String> EVIDENCE_TYPES = Set.of("application/pdf", "image/png", "image/jpeg",
            "audio/mpeg", "audio/wav", "audio/ogg", "text/plain", "message/rfc822");

    private final DisputeRepository disputes;
    private final DisputeEvidenceRepository evidences;
    private final CollectionCaseRepository cases;
    private final CaseAccess access;
    private final CaseSynchronizer synchronizer;
    private final CaseRecorder caseRecorder;
    private final DebtLedgerPort ledger;
    private final SettingsProvider settings;
    private final FileStorage fileStorage;
    private final DisputeFileCleanup fileCleanup;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final ApplicationEventPublisher events;
    private final CollectionMetrics metrics;
    private final CollectionProperties properties;
    private final Clock clock;

    DisputeService(DisputeRepository disputes, DisputeEvidenceRepository evidences, CollectionCaseRepository cases,
                   CaseAccess access, CaseSynchronizer synchronizer, CaseRecorder caseRecorder, DebtLedgerPort ledger,
                   SettingsProvider settings, FileStorage fileStorage, DisputeFileCleanup fileCleanup,
                   AccessScope accessScope, CurrentActor currentActor, AccessLogger accessLogger, AuditTrail auditTrail,
                   BusinessEventLogger businessEvents, ApplicationEventPublisher events, CollectionMetrics metrics,
                   CollectionProperties properties, Clock clock) {
        this.disputes = disputes;
        this.evidences = evidences;
        this.cases = cases;
        this.access = access;
        this.synchronizer = synchronizer;
        this.caseRecorder = caseRecorder;
        this.ledger = ledger;
        this.settings = settings;
        this.fileStorage = fileStorage;
        this.fileCleanup = fileCleanup;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.events = events;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public DisputeView open(UUID caseId, OpenDisputeCommand command) {
        Guard.require(command != null, "dispute.required", "Dados da contestação obrigatórios");
        CollectionCase c = access.forCommand(caseId);
        Guard.requireState(c.getStatus().isActive(), "case.closed", "Caso encerrado");
        if (!currentActor.get().has(Permission.DISPUTE_MANAGE)) {
            access.requireWorkable(c);
        }
        DisputeType type = CollectionEnums.parse(DisputeType.class, command.type(), "dispute.type_invalid", "Tipo");
        DisputeOrigin origin = CollectionEnums.parse(DisputeOrigin.class, command.origin(), "dispute.origin_invalid",
                "Origem");
        Map<UUID, TitleView> available = synchronizer.titlesInCollection(c).stream()
                .collect(Collectors.toMap(TitleView::id, Function.identity()));
        Guard.require(!available.isEmpty(), "dispute.no_titles", "O caso não tem títulos em cobrança para contestar");
        Set<UUID> titleIds = command.titleIds().isEmpty() ? available.keySet() : command.titleIds();
        Guard.require(available.keySet().containsAll(titleIds), "dispute.title_invalid",
                "Título contestado não pertence ao caso ou não está em cobrança");
        Money amount = titleIds.stream().map(id -> Money.of(available.get(id).openBalance())).reduce(Money.ZERO, Money::plus);
        LocalDate today = CollectionViews.today(clock);
        LocalDate due = command.responseDueDate() != null ? command.responseDueDate()
                : today.plusDays(settings.current().disputeResponseDays());
        Guard.require(!due.isBefore(today), "dispute.due_in_past", "Prazo de resposta no passado");
        boolean blocks = command.blocksCollection() == null || command.blocksCollection();
        Dispute dispute = disputes.save(new Dispute(c, titleIds, type, origin, command.description(), blocks, amount, due,
                Instant.now(clock)));
        ledger.markDisputed(titleIds, dispute.getId(), blocks);

        CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
        if (blocks) {
            c.enterHold(CaseStatus.DISPUTED, "Contestação aberta");
        }
        synchronizer.refreshPosition(c);
        synchronizer.applyEligibilityAndRoute(c, synchronizer.context());
        caseRecorder.commit(c, before, AssignmentMode.ROUTING, "Contestação aberta", "dispute_opened");

        auditTrail.record(AuditEntry.of("dispute.opened", "dispute", dispute.getId())
                .creditor(dispute.getCreditorId())
                .created("caseId", dispute.getCaseId())
                .created("type", dispute.getType())
                .created("origin", dispute.getOrigin())
                .created("titleIds", dispute.getTitleIds().stream().sorted().toList().toString())
                .created("blocksCollection", blocks)
                .created("disputedAmount", dispute.getDisputedAmount())
                .created("responseDueDate", dispute.getResponseDueDate()));
        businessEvents.log(CaseRecorder.base(BusinessEventTypes.DISPUTE_OPENED, c, today)
                .reference("dispute", dispute.getId())
                .amount(dispute.getDisputedAmount())
                .attr("type", type)
                .attr("origin", origin)
                .attr("blocksCollection", blocks)
                .attr("titles", titleIds.size())
                .attr("responseDays", ChronoUnit.DAYS.between(today, due)));
        publish(dispute);
        return view(dispute);
    }

    @Override
    public DisputeView startReview(UUID disputeId) {
        Dispute dispute = loadForCommand(disputeId);
        DisputeStatus previous = dispute.getStatus();
        dispute.startReview();
        Dispute saved = disputes.save(dispute);
        auditTrail.record(AuditEntry.of("dispute.review_started", "dispute", saved.getId())
                .creditor(saved.getCreditorId()).change("status", previous, saved.getStatus()));
        publish(saved);
        return view(saved);
    }

    @Override
    public DisputeView resolve(UUID disputeId, String outcome, String note) {
        Dispute dispute = loadForCommand(disputeId);
        DisputeOutcome parsed = CollectionEnums.parse(DisputeOutcome.class, outcome, "dispute.outcome_invalid", "Resultado");
        DisputeStatus previous = dispute.getStatus();
        dispute.resolve(parsed, note, currentActor.actorId(), Instant.now(clock));
        return close(dispute, previous);
    }

    @Override
    public DisputeView withdraw(UUID disputeId, String note) {
        Dispute dispute = loadForCommand(disputeId);
        DisputeStatus previous = dispute.getStatus();
        dispute.withdraw(note, currentActor.actorId(), Instant.now(clock));
        return close(dispute, previous);
    }

    /** Encerramento (resolução ou desistência): libera os títulos, retira a retenção e reavalia o caso. */
    private DisputeView close(Dispute dispute, DisputeStatus previous) {
        Dispute saved = disputes.save(dispute);
        ledger.clearDispute(saved.getTitleIds(), saved.getId());
        LocalDate today = CollectionViews.today(clock);
        access.lockDebtor(saved.getDebtorId());
        CollectionCase c = cases.findById(saved.getCaseId()).orElseThrow();
        if (c.getStatus().isActive()) {
            CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
            if (!disputes.existsBlockingByCaseId(c.getId())) {
                c.releaseHold(CaseStatus.DISPUTED, false, "Contestação encerrada");
            }
            synchronizer.refreshPosition(c);
            synchronizer.applyEligibilityAndRoute(c, synchronizer.context());
            caseRecorder.commit(c, before, AssignmentMode.ROUTING, "Contestação encerrada", "dispute_closed");
        }
        auditTrail.record(AuditEntry.of("dispute.closed", "dispute", saved.getId())
                .creditor(saved.getCreditorId())
                .reason(saved.getResolutionNote())
                .change("status", previous, saved.getStatus())
                .change("outcome", null, saved.getOutcome()));
        Instant now = Instant.now(clock);
        businessEvents.log(CaseRecorder.base(BusinessEventTypes.DISPUTE_RESOLVED, c, today)
                .reference("dispute", saved.getId())
                .amount(saved.getDisputedAmount())
                .durationMs(Duration.between(saved.getOpenedAt(), now).toMillis())
                .attr("type", saved.getType())
                .attr("origin", saved.getOrigin())
                .attr("status", saved.getStatus())
                .attr("outcome", saved.getOutcome())
                .attr("durationDays", Duration.between(saved.getOpenedAt(), now).toDays())
                .attr("withinDeadline", !today.isAfter(saved.getResponseDueDate()))
                .attr("evidences", evidences.countByDisputeId(saved.getId())));
        publish(saved);
        return view(saved);
    }

    @Override
    public EvidenceView attachEvidence(UUID disputeId, EvidenceUpload upload) {
        Dispute dispute = loadVisible(disputeId);
        accessScope.requireCreditor(dispute.getCreditorId());
        dispute.requireOpenForEvidence();
        Guard.require(upload != null && upload.content() != null && upload.size() > 0, "dispute.evidence_empty",
                "Arquivo de evidência vazio");
        Guard.require(upload.size() <= properties.evidence().maxSizeBytes(), "dispute.evidence_too_large",
                "Arquivo de evidência excede o tamanho máximo");
        String contentType = upload.contentType() == null ? ""
                : upload.contentType().toLowerCase(Locale.ROOT).split(";")[0].trim();
        Guard.require(EVIDENCE_TYPES.contains(contentType), "dispute.evidence_type_invalid",
                "Tipo de arquivo não aceito como evidência");
        if (evidences.countByDisputeId(dispute.getId()) >= DisputeEvidence.MAX_PER_DISPUTE) {
            throw new ConflictException("dispute.evidence_limit", "Limite de %d evidências por contestação"
                    .formatted(DisputeEvidence.MAX_PER_DISPUTE));
        }
        StoredFile stored = fileStorage.store(FileCategory.EVIDENCE, upload.fileName(), contentType, upload.content(),
                upload.size());
        fileCleanup.deleteOnRollback(stored.key());
        DisputeEvidence evidence = evidences.save(new DisputeEvidence(dispute, stored.key(), stored.originalName(),
                contentType, stored.size(), stored.sha256()));
        auditTrail.record(AuditEntry.of("dispute.evidence_attached", "dispute", dispute.getId())
                .creditor(dispute.getCreditorId())
                .created("evidenceId", evidence.getId())
                .created("fileName", evidence.getFileName())
                .created("sha256", evidence.getSha256())
                .meta("size", evidence.getSize()));
        return CollectionViews.evidence(evidence);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EvidenceView> listEvidence(UUID disputeId) {
        Dispute dispute = loadVisible(disputeId);
        return evidences.findByDisputeIdOrderByCreatedAtAsc(dispute.getId()).stream().map(CollectionViews::evidence).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public EvidenceContent openEvidence(UUID disputeId, UUID evidenceId) {
        Dispute dispute = loadVisible(disputeId);
        DisputeEvidence evidence = evidences.findById(evidenceId)
                .filter(e -> e.getDisputeId().equals(dispute.getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("dispute_evidence", evidenceId));
        accessLogger.log(AccessEvent.of(AccessEventType.FILE_DOWNLOADED, currentActor.get(),
                Map.of("resource", "dispute_evidence", "resourceId", evidenceId.toString(),
                        "category", FileCategory.EVIDENCE.name())));
        return new EvidenceContent(evidence.getFileName(), evidence.getContentType(), evidence.getSize(),
                fileStorage.open(evidence.getFileKey()));
    }

    @Override
    @Transactional(readOnly = true)
    public DisputeView get(UUID disputeId) {
        return view(loadVisible(disputeId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DisputeView> listByCase(UUID caseId) {
        CollectionCase c = access.visible(caseId);
        return disputes.findByCaseIdOrderByOpenedAtDesc(c.getId()).stream().map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DisputeView> search(UUID creditorId, Set<String> statuses, Boolean overdue, PageQuery page) {
        if (creditorId != null) {
            accessScope.requireVisible("creditor", creditorId, creditorId);
        }
        return PageResponse.of(disputes.search(access.creditorFilter(), creditorId,
                        CollectionEnums.parseSet(DisputeStatus.class, statuses, "dispute.status_invalid", "Status"), overdue,
                        page.toPageable(Sort.by("responseDueDate").ascending().and(Sort.by("id")))),
                this::view);
    }

    /**
     * Sinaliza contestações abertas com prazo de resposta vencido (WARN para alarme, auditoria e
     * {@code collection.disputes.overdue}).
     *
     * @return quantidade sinalizada neste lote
     */
    @Transactional(propagation = Propagation.MANDATORY)
    int flagOverdue(int limit) {
        LocalDate today = CollectionViews.today(clock);
        int flagged = 0;
        for (Dispute dispute : disputes.findOverdueCandidates(today, limit)) {
            if (dispute.flagOverdue(today)) {
                disputes.save(dispute);
                auditTrail.record(AuditEntry.of("dispute.overdue", "dispute", dispute.getId())
                        .creditor(dispute.getCreditorId())
                        .change("overdue", false, true)
                        .meta("responseDueDate", dispute.getResponseDueDate()));
                metrics.disputeOverdue(dispute.getType().name());
                LOG.atWarn().addKeyValue("disputeId", dispute.getId()).addKeyValue("type", dispute.getType().name())
                        .addKeyValue("responseDueDate", dispute.getResponseDueDate().toString())
                        .log("Contestação com prazo de resposta vencido");
                flagged++;
            }
        }
        return flagged;
    }

    private void publish(Dispute d) {
        metrics.dispute(d.getStatus().name());
        events.publishEvent(new DisputeStatusChanged(Ids.newId(), currentActor.tenantId(), Instant.now(clock), d.getId(),
                d.getCaseId(), d.getDebtorId(), d.getCreditorId(), d.getTitleIds().stream().sorted().toList(),
                d.getStatus().name(), d.isBlocking()));
    }

    private Dispute loadVisible(UUID disputeId) {
        Dispute dispute = disputes.findById(disputeId).orElseThrow(() -> ResourceNotFoundException.of("dispute", disputeId));
        accessScope.requireVisible("dispute", disputeId, dispute.getCreditorId());
        return dispute;
    }

    private Dispute loadForCommand(UUID disputeId) {
        Dispute dispute = loadVisible(disputeId);
        accessScope.requireCreditor(dispute.getCreditorId());
        return dispute;
    }

    private DisputeView view(Dispute d) {
        return CollectionViews.dispute(d, (int) evidences.countByDisputeId(d.getId()));
    }
}
