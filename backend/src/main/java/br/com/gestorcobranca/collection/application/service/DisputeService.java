package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases;
import br.com.gestorcobranca.collection.domain.model.AssignmentMode;
import br.com.gestorcobranca.collection.domain.model.CaseStatus;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.CreditorResponseSource;
import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DisputeAdjustmentAction;
import br.com.gestorcobranca.collection.domain.model.DisputeAssignmentMode;
import br.com.gestorcobranca.collection.domain.model.DisputeCreditorRequest;
import br.com.gestorcobranca.collection.domain.model.DisputeCreditorResponse;
import br.com.gestorcobranca.collection.domain.model.DisputeDebtorNotice;
import br.com.gestorcobranca.collection.domain.model.DisputeEvidence;
import br.com.gestorcobranca.collection.domain.model.DisputeOrigin;
import br.com.gestorcobranca.collection.domain.model.DisputeOutcome;
import br.com.gestorcobranca.collection.domain.model.DisputeSettings;
import br.com.gestorcobranca.collection.domain.model.DisputeStatus;
import br.com.gestorcobranca.collection.domain.model.DisputeType;
import br.com.gestorcobranca.collection.domain.repository.CollectionCaseRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeAssignmentRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeCreditorRequestRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeCreditorResponseRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeDeadlineRuleRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeDebtorNoticeRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeEvidenceRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeMailRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeSearchCriteria;
import br.com.gestorcobranca.collection.domain.repository.DisputeSettingsRepository;
import br.com.gestorcobranca.collection.domain.service.DisputeDeadlines;
import br.com.gestorcobranca.collection.domain.service.DisputeDeadlines.Deadlines;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
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
import br.com.gestorcobranca.shared.paging.ChangePage;
import br.com.gestorcobranca.shared.paging.ChangeQuery;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.storage.FileCategory;
import br.com.gestorcobranca.shared.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
class DisputeService implements DisputeUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(DisputeService.class);

    private final DisputeRepository disputes;
    private final DisputeEvidenceRepository evidences;
    private final br.com.gestorcobranca.collection.domain.repository.DisputeDocumentQuarantineRepository quarantine;
    private final DisputeAssignmentRepository assignmentHistory;
    private final DisputeCreditorRequestRepository creditorRequests;
    private final DisputeMailRepository mails;
    private final DisputeCreditorResponseRepository creditorResponses;
    private final DisputeDebtorNoticeRepository debtorNotices;
    private final DisputeDeadlineRuleRepository deadlineRules;
    private final DisputeSettingsRepository disputeSettings;
    private final CollectionCaseRepository cases;
    private final CaseAccess access;
    private final DisputeAccess disputeAccess;
    private final CaseSynchronizer synchronizer;
    private final CaseRecorder caseRecorder;
    private final DebtLedgerPort ledger;
    private final SettingsProvider settings;
    private final FileStorage fileStorage;
    private final DisputeEvidenceStore evidenceStore;
    private final DisputeAssignments assignments;
    private final DisputeCreditorChannel creditorChannel;
    private final DisputeDebtorResponder debtorResponder;
    private final DisputeAdjustments adjustments;
    private final DisputeViews views;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final ApplicationEventPublisher events;
    private final CollectionMetrics metrics;
    private final Clock clock;

    DisputeService(DisputeRepository disputes, DisputeEvidenceRepository evidences,
                   br.com.gestorcobranca.collection.domain.repository.DisputeDocumentQuarantineRepository quarantine,
                   DisputeAssignmentRepository assignmentHistory, DisputeCreditorRequestRepository creditorRequests,
                   DisputeMailRepository mails, DisputeCreditorResponseRepository creditorResponses,
                   DisputeDebtorNoticeRepository debtorNotices, DisputeDeadlineRuleRepository deadlineRules,
                   DisputeSettingsRepository disputeSettings, CollectionCaseRepository cases, CaseAccess access,
                   DisputeAccess disputeAccess, CaseSynchronizer synchronizer, CaseRecorder caseRecorder,
                   DebtLedgerPort ledger, SettingsProvider settings, FileStorage fileStorage,
                   DisputeEvidenceStore evidenceStore, DisputeAssignments assignments,
                   DisputeCreditorChannel creditorChannel, DisputeDebtorResponder debtorResponder,
                   DisputeAdjustments adjustments, DisputeViews views, AccessScope accessScope,
                   CurrentActor currentActor, AccessLogger accessLogger, AuditTrail auditTrail,
                   BusinessEventLogger businessEvents, ApplicationEventPublisher events, CollectionMetrics metrics,
                   Clock clock) {
        this.disputes = disputes;
        this.evidences = evidences;
        this.quarantine = quarantine;
        this.assignmentHistory = assignmentHistory;
        this.creditorRequests = creditorRequests;
        this.mails = mails;
        this.creditorResponses = creditorResponses;
        this.debtorNotices = debtorNotices;
        this.deadlineRules = deadlineRules;
        this.disputeSettings = disputeSettings;
        this.cases = cases;
        this.access = access;
        this.disputeAccess = disputeAccess;
        this.synchronizer = synchronizer;
        this.caseRecorder = caseRecorder;
        this.ledger = ledger;
        this.settings = settings;
        this.fileStorage = fileStorage;
        this.evidenceStore = evidenceStore;
        this.assignments = assignments;
        this.creditorChannel = creditorChannel;
        this.debtorResponder = debtorResponder;
        this.adjustments = adjustments;
        this.views = views;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    // =================================================================== abertura e ciclo de vida

    @Override
    public DisputeView open(UUID caseId, OpenDisputeCommand command) {
        Guard.require(command != null, "dispute.required", "Dados da contestação obrigatórios");
        CollectionCase c = access.forCommand(caseId);
        Guard.requireState(c.getStatus().isActive(), "case.closed", "Caso encerrado");
        if (!currentActor.get().has(Permission.DISPUTE_MANAGE)) {
            access.requireWorkable(c);
        }
        return view(openFor(c, command, null));
    }

    /**
     * Abre a contestação no caso já carregado e validado pelo chamador. Uso interno do módulo: a ação
     * automática da tabulação ({@code InteractionService}) chama com a interação de origem, na mesma
     * transação do registro.
     */
    Dispute openFor(CollectionCase c, OpenDisputeCommand command, UUID originInteractionId) {
        return openFor(c, command, originInteractionId, null, null);
    }

    /**
     * Abertura com origem externa: {@code receivedAt} é quando o devedor contestou (o prazo do CDC conta dali, mesmo
     * que o registro aqui aconteça depois); {@code originReference} identifica o pedido de origem (idempotência).
     */
    Dispute openFor(CollectionCase c, OpenDisputeCommand command, UUID originInteractionId, String originReference,
                    Instant receivedAt) {
        Guard.require(command != null, "dispute.required", "Dados da contestação obrigatórios");
        Guard.requireState(c.getStatus().isActive(), "case.closed", "Caso encerrado");
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
        Instant openedAt = receivedAt == null ? Instant.now(clock) : receivedAt;
        LocalDate today = openedAt.atZone(br.com.gestorcobranca.shared.config.TimeConfig.BUSINESS_ZONE).toLocalDate();
        Guard.require(command.responseDueDate() == null || !command.responseDueDate().isBefore(today),
                "dispute.due_in_past", "Prazo de resposta no passado");
        Deadlines deadlines = deadlines(c.getCreditorId(), type, command.responseDueDate(), today);
        boolean blocks = command.blocksCollection() == null || command.blocksCollection();
        Dispute opened = new Dispute(c, titleIds, type, origin, command.description(), blocks, amount,
                deadlines.responseDue(), deadlines.internalDue(), deadlines.source(), originInteractionId, openedAt);
        opened.originatedFrom(originReference);
        Dispute dispute = disputes.save(opened);
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
                .created("responseDueDate", dispute.getResponseDueDate())
                .created("internalDueDate", dispute.getInternalDueDate())
                .created("deadlineSource", dispute.getDeadlineSource())
                .created("originInteractionId", originInteractionId)
                .meta("originReference", originReference));
        businessEvents.log(CaseRecorder.base(BusinessEventTypes.DISPUTE_OPENED, c, today)
                .reference("dispute", dispute.getId())
                .amount(dispute.getDisputedAmount())
                .attr("type", type)
                .attr("origin", origin)
                .attr("blocksCollection", blocks)
                .attr("titles", titleIds.size())
                .attr("responseDays", ChronoUnit.DAYS.between(today, dispute.getResponseDueDate()))
                .attr("internalDays", ChronoUnit.DAYS.between(today, dispute.getInternalDueDate()))
                .attr("deadlineSource", dispute.getDeadlineSource())
                .attr("trigger", originReference != null ? "portal"
                        : originInteractionId == null ? "manual" : "tabulation"));
        // a abertura pela tabulação segue a mesma distribuição (manual = sem responsável; automática = menos carregado)
        assignments.autoAssign(dispute);
        publish(dispute);
        return dispute;
    }

    /** Contestação ainda em aberto (aberta ou em análise) no caso, a mais recente. */
    Optional<Dispute> openOn(UUID caseId) {
        return disputes.findByCaseIdOrderByOpenedAtDesc(caseId).stream().filter(d -> d.getStatus().isOpen()).findFirst();
    }

    /** O caso ainda tem títulos em cobrança que podem ser contestados. */
    boolean hasTitlesToDispute(CollectionCase c) {
        return !synchronizer.titlesInCollection(c).isEmpty();
    }

    DisputeView viewOf(Dispute dispute) {
        return view(dispute);
    }

    @Override
    public DisputeView startReview(UUID disputeId) {
        Dispute dispute = loadForWork(disputeId);
        DisputeStatus previous = dispute.getStatus();
        dispute.startReview();
        Dispute saved = disputes.save(dispute);
        auditTrail.record(AuditEntry.of("dispute.review_started", "dispute", saved.getId())
                .creditor(saved.getCreditorId()).change("status", previous, saved.getStatus()));
        publish(saved);
        return view(saved);
    }

    @Override
    public DisputeView resolve(UUID disputeId, ResolveCommand command) {
        Guard.require(command != null, "dispute.required", "Dados da resolução obrigatórios");
        Dispute dispute = loadForWork(disputeId);
        DisputeOutcome parsed = CollectionEnums.parse(DisputeOutcome.class, command.outcome(), "dispute.outcome_invalid",
                "Resultado");
        DisputeAdjustmentAction action = DisputeAdjustments.actionFor(dispute, parsed, command.adjustmentAction());
        DisputeStatus previous = dispute.getStatus();
        Money baseline = action == null ? null : adjustments.openBalance(dispute);
        dispute.resolve(parsed, command.note(), currentActor.actorId(), Instant.now(clock), action, baseline);
        DisputeView view = close(dispute, previous);
        adjustments.suggested(dispute);
        return view;
    }

    @Override
    public DisputeView withdraw(UUID disputeId, String note) {
        Dispute dispute = loadForWork(disputeId);
        DisputeStatus previous = dispute.getStatus();
        dispute.withdraw(note, currentActor.actorId(), Instant.now(clock));
        return close(dispute, previous);
    }

    /** Encerramento (resolução ou desistência): libera os títulos, retira a retenção e reavalia o caso. */
    private DisputeView close(Dispute dispute, DisputeStatus previous) {
        Dispute saved = disputes.save(dispute);
        creditorChannel.cancelPending(saved);
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
                .change("outcome", null, saved.getOutcome())
                .meta("adjustmentAction", saved.getAdjustmentAction()));
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
                .attr("withinInternalDeadline", !today.isAfter(saved.getInternalDueDate()))
                .attr("assigned", saved.getAssigneeId() != null)
                .attr("creditorRequests", creditorRequests.countByDisputeId(saved.getId()))
                .attr("adjustmentAction", saved.getAdjustmentAction())
                .attr("evidences", evidences.countByDisputeId(saved.getId())));
        publish(saved);
        return view(saved);
    }

    // =================================================================== evidências

    @Override
    public EvidenceView attachEvidence(UUID disputeId, EvidenceUpload upload) {
        Dispute dispute = disputeAccess.forCommand(disputeId);
        Actor actor = currentActor.get();
        if (actor.isUser() && !actor.has(Permission.DISPUTE_MANAGE) && !actor.has(Permission.CASE_WORK)) {
            takeIfNeeded(dispute);
        }
        DisputeEvidence evidence = evidenceStore.store(dispute, upload, null);
        return views.evidences(List.of(evidence)).getFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EvidenceView> listEvidence(UUID disputeId) {
        Dispute dispute = disputeAccess.visible(disputeId);
        return views.evidences(evidences.findByDisputeIdOrderByCreatedAtAsc(dispute.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public EvidenceContent openEvidence(UUID disputeId, UUID evidenceId) {
        Dispute dispute = disputeAccess.visible(disputeId);
        DisputeEvidence evidence = evidences.findById(evidenceId)
                .filter(e -> e.getDisputeId().equals(dispute.getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("dispute_evidence", evidenceId));
        accessLogger.log(AccessEvent.of(AccessEventType.FILE_DOWNLOADED, currentActor.get(),
                Map.of("resource", "dispute_evidence", "resourceId", evidenceId.toString(),
                        "category", FileCategory.EVIDENCE.name())));
        return new EvidenceContent(evidence.getFileName(), evidence.getContentType(), evidence.getSize(),
                fileStorage.open(evidence.getFileKey()));
    }

    // =================================================================== consultas

    @Override
    @Transactional(readOnly = true)
    public DisputeView get(UUID disputeId) {
        return view(disputeAccess.visible(disputeId));
    }

    @Override
    @Transactional(readOnly = true)
    public DisputeDetail detail(UUID disputeId) {
        Dispute d = disputeAccess.visible(disputeId);
        List<DisputeCreditorRequest> requests = creditorRequests.findByDisputeIdOrderBySequenceNumberAsc(d.getId());
        return new DisputeDetail(view(d),
                views.assignments(assignmentHistory.findByDisputeIdOrderByAssignedAtAsc(d.getId())),
                views.creditorRequests(requests, mails.findByRequestIdIn(requests.stream()
                        .map(DisputeCreditorRequest::getId).toList())),
                views.creditorResponses(creditorResponses.findByDisputeIdOrderByReceivedAtAsc(d.getId()),
                        evidences.findByDisputeIdOrderByCreatedAtAsc(d.getId()), quarantine.findByDisputeId(d.getId())),
                views.debtorNotices(debtorNotices.findByDisputeIdOrderByCreatedAtAsc(d.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DisputeView> listByCase(UUID caseId) {
        CollectionCase c = access.visible(caseId);
        return views.views(disputes.findByCaseIdOrderByOpenedAtDesc(c.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public ChangePage<DisputeView> changes(UUID creditorId, ChangeQuery query) {
        Guard.require(creditorId != null, "dispute.creditor_required", "Credor obrigatório");
        accessScope.requireVisible("creditor", creditorId, creditorId);
        List<Dispute> rows = disputes.findChanges(creditorId, query);
        ChangePage<Dispute> page = ChangePage.of(rows, query.limit(), Dispute::getUpdatedAt, Dispute::getId,
                Dispute::getVersion, d -> d);
        Map<UUID, DisputeView> byId = views.views(page.items().stream().map(ChangePage.Entry::value).toList())
                .stream().collect(Collectors.toMap(DisputeView::id, v -> v));
        return page.map(d -> byId.get(d.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DisputeView> search(DisputeQuery q, PageQuery page) {
        DisputeQuery query = q == null ? new DisputeQuery(null, null, null, null, null, false, false, null, null, null,
                false, false, false) : q;
        if (query.creditorId() != null) {
            accessScope.requireVisible("creditor", query.creditorId(), query.creditorId());
        }
        UUID assignee = null;
        if (query.mine() || "me".equalsIgnoreCase(query.assignee())) {
            assignee = currentActor.actorId();
        } else if (query.assignee() != null && !query.assignee().isBlank()) {
            try {
                assignee = UUID.fromString(query.assignee().trim());
            } catch (IllegalArgumentException e) {
                throw new br.com.gestorcobranca.shared.domain.BusinessRuleException("dispute.assignee_invalid",
                        "Responsável inválido");
            }
        }
        DisputeSearchCriteria criteria = new DisputeSearchCriteria(access.creditorFilter(), query.creditorId(),
                CollectionEnums.parseSet(DisputeStatus.class, query.statuses(), "dispute.status_invalid", "Status"),
                CollectionEnums.parseSet(DisputeType.class, query.types(), "dispute.type_invalid", "Tipo"),
                CollectionEnums.parseSet(DisputeOrigin.class, query.origins(), "dispute.origin_invalid", "Origem"),
                assignee, query.unassigned(), query.overdue(), query.dueUntil(), query.awaitingCreditor(),
                query.noticePending(), query.adjustmentPending(),
                query.supervision() ? CollectionViews.today(clock) : null, query.mailReplyPending(), query.debtorId());
        Page<Dispute> result = disputes.search(criteria, page.toPageable(Sort.by("internalDueDate").ascending()
                .and(Sort.by("responseDueDate")).and(Sort.by("id"))));
        return new PageResponse<>(views.views(result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    // =================================================================== responsável

    @Override
    public DisputeView assign(UUID disputeId, UUID assigneeId, String reason) {
        disputeAccess.requireManage();
        Dispute d = disputeAccess.forCommand(disputeId);
        return view(assignments.change(d, assigneeId, assigneeId == null ? DisputeAssignmentMode.RELEASE
                : DisputeAssignmentMode.MANUAL, reason));
    }

    @Override
    public DisputeView take(UUID disputeId) {
        Dispute d = disputeAccess.forCommand(disputeId);
        Actor actor = currentActor.get();
        Guard.requireState(actor.isUser(), "dispute.take_requires_user", "Somente usuários assumem contestações");
        if (!actor.has(Permission.DISPUTE_MANAGE)) {
            accessScope.requirePermission(Permission.DISPUTE_WORK);
            Guard.requireState(d.getAssigneeId() == null || d.isAssignedTo(actor.id()), "dispute.assigned_to_other",
                    "A contestação está com outro responsável");
        }
        return view(assignments.change(d, actor.id(), DisputeAssignmentMode.TAKE, null));
    }

    @Override
    public RedistributionResult redistribute(UUID fromAssigneeId, UUID toAssigneeId, String reason) {
        disputeAccess.requireManage();
        return assignments.redistribute(fromAssigneeId, toAssigneeId, reason, access.creditorFilter());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssigneeOption> assigneeOptions(UUID disputeId) {
        Dispute d = disputeAccess.visible(disputeId);
        return assignments.options(d.getCreditorId());
    }

    // =================================================================== credor

    @Override
    @Transactional(readOnly = true)
    public CreditorRequestDraft draftCreditorRequest(UUID disputeId) {
        Dispute d = disputeAccess.forCommand(disputeId);
        Guard.requireState(d.getStatus().isOpen(), "dispute.not_open", "Contestação já encerrada");
        DisputeView view = view(d);
        return creditorChannel.draft(d, view, views.evidences(evidences.findByDisputeIdOrderByCreatedAtAsc(d.getId())));
    }

    @Override
    public CreditorRequestView requestCreditor(UUID disputeId, CreditorRequestCommand command) {
        Dispute d = loadForWork(disputeId);
        DisputeStatus previous = d.getStatus();
        DisputeCreditorRequest request = creditorChannel.send(d, view(d).titles(), command);
        if (previous != d.getStatus()) {
            publish(d);
        }
        return views.creditorRequests(List.of(request), mails.findByRequestIdIn(List.of(request.getId()))).getFirst();
    }

    @Override
    public CreditorResponseView registerCreditorResponse(UUID disputeId, CreditorResponseCommand command,
                                                         List<EvidenceUpload> documents) {
        Actor actor = currentActor.get();
        Dispute d;
        CreditorResponseSource source;
        if (actor.type() == Actor.ActorType.INTEGRATION) {
            d = disputeAccess.forCommand(disputeId);
            source = CreditorResponseSource.PUBLIC_API;
        } else {
            d = loadForWork(disputeId);
            source = CreditorResponseSource.MANUAL;
        }
        DisputeCreditorResponse response = creditorChannel.respond(d, command, documents, source);
        return views.creditorResponses(List.of(response), evidences.findByDisputeIdOrderByCreatedAtAsc(d.getId()),
                quarantine.findByDisputeId(d.getId())).getFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CreditorRequestView> creditorRequests(UUID disputeId) {
        Dispute d = disputeAccess.visible(disputeId);
        List<DisputeCreditorRequest> requests = creditorRequests.findByDisputeIdOrderBySequenceNumberAsc(d.getId());
        return views.creditorRequests(requests, mails.findByRequestIdIn(requests.stream()
                .map(DisputeCreditorRequest::getId).toList()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CreditorResponseView> creditorResponses(UUID disputeId) {
        Dispute d = disputeAccess.visible(disputeId);
        return views.creditorResponses(creditorResponses.findByDisputeIdOrderByReceivedAtAsc(d.getId()),
                evidences.findByDisputeIdOrderByCreatedAtAsc(d.getId()), quarantine.findByDisputeId(d.getId()));
    }

    // =================================================================== devedor

    @Override
    @Transactional(readOnly = true)
    public DebtorNoticeDraft draftDebtorNotice(UUID disputeId) {
        Dispute d = disputeAccess.forCommand(disputeId);
        return debtorResponder.draft(d, view(d).titles());
    }

    @Override
    public DebtorNoticeView notifyDebtor(UUID disputeId, DebtorNoticeCommand command) {
        Dispute d = disputeAccess.forCommand(disputeId);
        // resolvida sem responsável: quem tem DISPUTE_WORK responde sem assumir (a contestação já está encerrada)
        disputeAccess.requireWork(d);
        DisputeDebtorNotice notice = debtorResponder.send(d, command);
        return views.debtorNotices(List.of(notice)).getFirst();
    }

    // =================================================================== ajuste

    @Override
    public DisputeView dismissAdjustment(UUID disputeId, String reason) {
        disputeAccess.requireManage();
        Dispute d = disputeAccess.forCommand(disputeId);
        return view(adjustments.dismiss(d, reason));
    }

    // =================================================================== tarefas do módulo

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

    // =================================================================== apoio

    private Deadlines deadlines(UUID creditorId, DisputeType type, LocalDate informed, LocalDate today) {
        DisputeSettings.Values values = disputeSettings.findFirstByOrderByCreatedAtAsc().map(DisputeSettings::values)
                .orElse(DisputeSettings.Values.DEFAULTS);
        return DisputeDeadlines.resolve(today, creditorId, type, informed, deadlineRules.findByActiveTrue(),
                settings.current().disputeResponseDays(), values.internalDays());
    }

    /** Carrega para trabalho: escopo de comando, regra do responsável e "pegar para mim" automático. */
    private Dispute loadForWork(UUID disputeId) {
        Dispute dispute = disputeAccess.forCommand(disputeId);
        takeIfNeeded(dispute);
        return dispute;
    }

    private void takeIfNeeded(Dispute dispute) {
        if (disputeAccess.requireWork(dispute) && dispute.getStatus().isOpen()) {
            assignments.change(dispute, currentActor.actorId(), DisputeAssignmentMode.TAKE,
                    "Assumida ao trabalhar a contestação");
        }
    }

    private void publish(Dispute d) {
        metrics.dispute(d.getStatus().name());
        events.publishEvent(new DisputeStatusChanged(Ids.newId(), currentActor.tenantId(), Instant.now(clock), d.getId(),
                d.getCaseId(), d.getDebtorId(), d.getCreditorId(), d.getTitleIds().stream().sorted().toList(),
                d.getStatus().name(), d.isBlocking()));
    }

    private DisputeView view(Dispute d) {
        return views.view(d);
    }

    /** Usado pelo consumo de eventos: ajustes sugeridos cujo efeito aparece nos títulos do devedor. */
    @Transactional(propagation = Propagation.MANDATORY)
    int reconcileAdjustments(UUID debtorId) {
        return adjustments.reconcile(debtorId);
    }
}
