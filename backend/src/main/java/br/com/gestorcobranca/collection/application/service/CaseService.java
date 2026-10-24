package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputedTitle;
import br.com.gestorcobranca.collection.domain.model.AssignmentMode;
import br.com.gestorcobranca.collection.domain.model.CaseStatus;
import br.com.gestorcobranca.collection.domain.model.CloseReason;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.OwnerType;
import br.com.gestorcobranca.collection.domain.model.PromiseStatus;
import br.com.gestorcobranca.collection.domain.model.TaskStatus;
import br.com.gestorcobranca.collection.domain.repository.CaseSearchCriteria;
import br.com.gestorcobranca.collection.domain.repository.CollectionCaseRepository;
import br.com.gestorcobranca.collection.domain.repository.CollectionTaskRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeRepository;
import br.com.gestorcobranca.collection.domain.repository.InteractionRepository;
import br.com.gestorcobranca.collection.domain.repository.PaymentPromiseRepository;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.RestrictionFilter;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.RestrictionView;
import br.com.gestorcobranca.debt.application.port.in.DebtorSheetQueries;
import br.com.gestorcobranca.debt.application.port.in.DebtorSheetQueries.DebtorSummary;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Service
@Transactional
class CaseService implements CaseUseCases {

    private static final int REASON_MAX = 500;
    private static final int SHEET_INTERACTIONS = 10;
    private static final int SHEET_RESTRICTIONS = 50;
    private static final int MAX_ACTIVE_PAGE = 500;

    private final CollectionCaseRepository cases;
    private final CaseAccess access;
    private final CaseSynchronizer synchronizer;
    private final CaseRecorder recorder;
    private final DisputeRepository disputes;
    private final DisputeViews disputeViews;
    private final PaymentPromiseRepository promises;
    private final CollectionTaskRepository tasks;
    private final InteractionRepository interactions;
    private final DebtorSheetQueries debtorSheet;
    private final ContactRestrictionUseCases restrictions;
    private final AccessScope accessScope;
    private final Clock clock;

    CaseService(CollectionCaseRepository cases, CaseAccess access, CaseSynchronizer synchronizer, CaseRecorder recorder,
                DisputeRepository disputes, DisputeViews disputeViews, PaymentPromiseRepository promises,
                CollectionTaskRepository tasks, InteractionRepository interactions, DebtorSheetQueries debtorSheet,
                ContactRestrictionUseCases restrictions, AccessScope accessScope, Clock clock) {
        this.cases = cases;
        this.access = access;
        this.synchronizer = synchronizer;
        this.recorder = recorder;
        this.disputes = disputes;
        this.disputeViews = disputeViews;
        this.promises = promises;
        this.tasks = tasks;
        this.interactions = interactions;
        this.debtorSheet = debtorSheet;
        this.restrictions = restrictions;
        this.accessScope = accessScope;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public CaseView get(UUID caseId) {
        return CollectionViews.caseView(access.visible(caseId), CollectionViews.today(clock));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CaseView> search(CaseFilter filter, PageQuery page) {
        if (filter.creditorId() != null) {
            accessScope.requireVisible("creditor", filter.creditorId(), filter.creditorId());
        }
        CaseSearchCriteria criteria = new CaseSearchCriteria(access.creditorFilter(), filter.creditorId(),
                filter.portfolioId(), filter.debtorId(),
                CollectionEnums.parseSet(CaseStatus.class, filter.statuses(), "case.status_invalid", "Status do caso"),
                filter.queueId(), filter.assigneeUserId(),
                CollectionEnums.parseOptional(OwnerType.class, filter.ownerType(), "case.owner_type_invalid", "Dono"),
                filter.eligible());
        LocalDate today = CollectionViews.today(clock);
        return PageResponse.of(cases.search(criteria, page.toPageable(Sort.by(Sort.Direction.DESC, "openedAt")
                .and(Sort.by("id")))), c -> CollectionViews.caseView(c, today));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CaseView> listByDebtor(UUID debtorId) {
        LocalDate today = CollectionViews.today(clock);
        return cases.findByDebtorIdOrderByOpenedAtDesc(debtorId).stream()
                .filter(c -> accessScope.canAccessCreditor(c.getCreditorId()))
                .map(c -> CollectionViews.caseView(c, today)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ActiveCasePage listActiveAfter(UUID afterCaseId, int limit) {
        Guard.require(limit > 0 && limit <= MAX_ACTIVE_PAGE, "case.page_size_invalid", "Página entre 1 e 500 casos");
        List<UUID> ids = cases.findActiveIdsAfter(afterCaseId, limit);
        if (ids.isEmpty()) {
            return new ActiveCasePage(List.of(), null);
        }
        LocalDate today = CollectionViews.today(clock);
        List<CaseView> views = cases.findAllByIdIn(ids).stream()
                .filter(c -> accessScope.canAccessCreditor(c.getCreditorId()))
                .sorted(java.util.Comparator.comparing(CollectionCase::getId))
                .map(c -> CollectionViews.caseView(c, today)).toList();
        return new ActiveCasePage(views, ids.size() == limit ? ids.getLast() : null);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CaseView> findActive(UUID debtorId, UUID portfolioId) {
        LocalDate today = CollectionViews.today(clock);
        return cases.findActiveByDebtorAndPortfolio(debtorId, portfolioId)
                .filter(c -> accessScope.canAccessCreditor(c.getCreditorId()))
                .map(c -> CollectionViews.caseView(c, today));
    }

    @Override
    @Transactional(readOnly = true)
    public CaseSheet sheet(UUID caseId) {
        CollectionCase c = access.visible(caseId);
        LocalDate today = CollectionViews.today(clock);
        DebtorSummary summary = debtorSheet.summary(c.getDebtorId());
        DebtorInfo debtor = new DebtorInfo(summary.debtorId(), summary.name(), summary.maskedDocument(),
                summary.personType(), summary.status(), summary.contacts().stream()
                .map(k -> new ContactInfo(k.contactId(), k.type(), k.maskedValue(), k.label(), k.whatsapp(), k.status(),
                        k.priority())).toList());
        List<TitleInfo> titles = summary.creditors().stream()
                .filter(p -> p.creditorId().equals(c.getCreditorId()))
                .flatMap(p -> p.titles().stream())
                .filter(t -> t.portfolioId().equals(c.getPortfolioId()))
                .map(t -> new TitleInfo(t.titleId(), t.externalId(), t.installmentNumber(), t.dueDate(),
                        t.principalAmount(), t.openBalance(), t.status(), t.disputeBlocked(), t.daysOverdue()))
                .toList();
        List<RestrictionInfo> activeRestrictions = restrictions.search(
                        new RestrictionFilter(c.getDebtorId(), null, null, "ACTIVE", null), new PageQuery(0, SHEET_RESTRICTIONS))
                .content().stream()
                .filter(RestrictionView::effective)
                .filter(r -> r.creditorId() == null || r.creditorId().equals(c.getCreditorId()))
                .map(r -> new RestrictionInfo(r.id(), r.type(), r.channel(), r.creditorId(), r.contactMasked(),
                        r.startsAt(), r.endsAt()))
                .toList();
        var sheetTitles = DisputeViews.index(titles.stream().map(t -> new DisputedTitle(t.titleId(), t.externalId(),
                t.installmentNumber(), t.dueDate(), t.principalAmount(), t.openBalance())).toList());
        List<br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView> openDisputes =
                disputes.findByCaseIdOrderByOpenedAtDesc(c.getId()).stream()
                        .filter(d -> d.getStatus().isOpen())
                        .map(d -> disputeViews.view(d, sheetTitles))
                        .toList();
        var pendingPromise = promises.findByCaseIdAndStatus(c.getId(), PromiseStatus.PENDING).stream().findFirst()
                .map(CollectionViews::promise).orElse(null);
        var openTasks = tasks.findByCaseIdAndStatusIn(c.getId(), EnumSet.of(TaskStatus.PENDING, TaskStatus.MISSED))
                .stream().map(t -> CollectionViews.task(t, c.getDebtorId())).toList();
        var recent = interactions.findByCaseIdOrderByStartedAtDesc(c.getId(), PageRequest.of(0, SHEET_INTERACTIONS))
                .map(CollectionViews::interaction).getContent();
        List<CaseView> others = cases.findByDebtorIdOrderByOpenedAtDesc(c.getDebtorId()).stream()
                .filter(o -> !o.getId().equals(c.getId()) && accessScope.canAccessCreditor(o.getCreditorId()))
                .map(o -> CollectionViews.caseView(o, today)).toList();
        return new CaseSheet(CollectionViews.caseView(c, today), debtor, titles, activeRestrictions, openDisputes,
                pendingPromise, openTasks, recent, others);
    }

    @Override
    public CaseView suspend(UUID caseId, String reason) {
        return transition(caseId, reason, c -> c.enterHold(CaseStatus.SUSPENDED, reason));
    }

    @Override
    public CaseView resume(UUID caseId, String reason) {
        return transition(caseId, reason, c -> {
            Guard.requireState(c.getStatus() == CaseStatus.SUSPENDED, "case.not_suspended", "Caso não está suspenso");
            c.releaseHold(CaseStatus.SUSPENDED, disputes.existsBlockingByCaseId(c.getId()), reason);
        });
    }

    @Override
    public CaseView sendToLegal(UUID caseId, String reason) {
        return transition(caseId, reason, c -> c.enterHold(CaseStatus.LEGAL, reason));
    }

    @Override
    public CaseView returnFromLegal(UUID caseId, String reason) {
        return transition(caseId, reason, c -> {
            Guard.requireState(c.getStatus() == CaseStatus.LEGAL, "case.not_legal", "Caso não está no jurídico");
            c.releaseHold(CaseStatus.LEGAL, disputes.existsBlockingByCaseId(c.getId()), reason);
        });
    }

    @Override
    public CaseView close(UUID caseId, String reason) {
        requireReason(reason);
        CollectionCase c = access.forCommand(caseId);
        Guard.requireState(c.getStatus().isActive(), "case.closed", "Caso encerrado");
        return CollectionViews.caseView(synchronizer.close(c, CloseReason.MANUAL, reason.trim(), "manual"),
                CollectionViews.today(clock));
    }

    @Override
    public CaseView refresh(UUID caseId) {
        CollectionCase c = access.forCommand(caseId);
        Guard.requireState(c.getStatus().isActive(), "case.closed", "Caso encerrado");
        CollectionCase synced = synchronizer.syncCase(c, "refresh").orElse(c);
        return CollectionViews.caseView(synced, CollectionViews.today(clock));
    }

    private CaseView transition(UUID caseId, String reason, Consumer<CollectionCase> change) {
        requireReason(reason);
        CollectionCase c = access.forCommand(caseId);
        CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
        change.accept(c);
        synchronizer.applyEligibilityAndRoute(c, synchronizer.context());
        return CollectionViews.caseView(recorder.commit(c, before, AssignmentMode.ROUTING, reason.trim(), "manual"),
                CollectionViews.today(clock));
    }

    private static void requireReason(String reason) {
        Guard.require(reason != null && !reason.isBlank() && reason.trim().length() <= REASON_MAX, "case.reason_required",
                "Motivo obrigatório (até %d caracteres)".formatted(REASON_MAX));
    }
}
