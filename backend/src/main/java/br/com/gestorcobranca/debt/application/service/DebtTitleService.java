package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.service.TitleChangeNotifier.Trigger;
import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.LedgerComponent;
import br.com.gestorcobranca.debt.domain.model.OwnershipReleaseType;
import br.com.gestorcobranca.debt.domain.model.TitleBalanceSnapshot;
import br.com.gestorcobranca.debt.domain.model.TitleLedgerEntry;
import br.com.gestorcobranca.debt.domain.model.TitleOwnership;
import br.com.gestorcobranca.debt.domain.model.TitleStatus;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.debt.domain.repository.TitleBalanceSnapshotRepository;
import br.com.gestorcobranca.debt.domain.repository.TitleLedgerEntryRepository;
import br.com.gestorcobranca.debt.domain.repository.TitleOwnershipRepository;
import br.com.gestorcobranca.debt.domain.repository.TitleSearchCriteria;
import br.com.gestorcobranca.debt.domain.service.TitleBalance;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.PortfolioView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.domain.PreconditionFailedException;
import br.com.gestorcobranca.shared.paging.ChangePage;
import br.com.gestorcobranca.shared.paging.ChangeQuery;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@Service
@Transactional
class DebtTitleService implements DebtTitleUseCases {

    private static final int MAX_BATCH = 500;

    private final DebtTitleRepository titles;
    private final TitleLedgerEntryRepository ledger;
    private final TitleBalanceSnapshotRepository snapshots;
    private final TitleOwnershipRepository ownerships;
    private final DebtorVisibility debtorVisibility;
    private final PortfolioUseCases portfolios;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;
    private final TitleChangeNotifier notifier;
    private final Clock clock;

    DebtTitleService(DebtTitleRepository titles, TitleLedgerEntryRepository ledger,
                     TitleBalanceSnapshotRepository snapshots, TitleOwnershipRepository ownerships,
                     DebtorVisibility debtorVisibility, PortfolioUseCases portfolios, AccessScope accessScope,
                     AuditTrail auditTrail, TitleChangeNotifier notifier, Clock clock) {
        this.titles = titles;
        this.ledger = ledger;
        this.snapshots = snapshots;
        this.ownerships = ownerships;
        this.debtorVisibility = debtorVisibility;
        this.portfolios = portfolios;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
        this.notifier = notifier;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- consultas

    @Override
    @Transactional(readOnly = true)
    public TitleView get(UUID titleId) {
        return view(loadVisible(titleId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TitleView> listByDebtor(UUID debtorId) {
        debtorVisibility.requireVisible(debtorId);
        LocalDate today = DebtViews.today(clock);
        return titles.findByDebtor(debtorId, debtorVisibility.allowedCreditors()).stream()
                .map(t -> DebtViews.title(t, today)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TitleView> search(TitleSearch filter, PageQuery page) {
        LocalDate today = DebtViews.today(clock);
        Collection<TitleStatus> statuses = filter.status() == null || filter.status().isBlank() ? null
                : EnumSet.of(DebtViews.parseEnum(TitleStatus.class, filter.status(), "title.status_invalid", "Status do título"));
        LocalDate dueOnOrAfter = filter.dueFrom();
        LocalDate dueOnOrBefore = filter.dueTo();
        if (filter.minOverdueDays() != null || filter.maxOverdueDays() != null) {
            Guard.require(filter.minOverdueDays() == null || filter.minOverdueDays() >= 0, "title.overdue_filter_invalid",
                    "Atraso mínimo não pode ser negativo");
            Guard.require(filter.maxOverdueDays() == null || filter.maxOverdueDays() >= 0, "title.overdue_filter_invalid",
                    "Atraso máximo não pode ser negativo");
            // atraso só existe para títulos em cobrança
            EnumSet<TitleStatus> inCollection = EnumSet.of(TitleStatus.OPEN, TitleStatus.SUSPENDED);
            if (statuses != null) {
                inCollection.retainAll(statuses);
            }
            statuses = inCollection;
            if (filter.minOverdueDays() != null && filter.minOverdueDays() > 0) {
                dueOnOrBefore = earliest(dueOnOrBefore, today.minusDays(filter.minOverdueDays()));
            }
            if (filter.maxOverdueDays() != null) {
                dueOnOrAfter = latest(dueOnOrAfter, today.minusDays(filter.maxOverdueDays()));
            }
        }
        TitleSearchCriteria criteria = new TitleSearchCriteria(debtorVisibility.allowedCreditors(), filter.creditorId(),
                filter.portfolioId(), filter.debtorId(), statuses, dueOnOrAfter, dueOnOrBefore);
        return PageResponse.of(titles.search(criteria, page.toPageable(Sort.by("dueDate", "id"))),
                t -> DebtViews.title(t, today));
    }

    @Override
    @Transactional(readOnly = true)
    public TitleBalanceView balance(UUID titleId) {
        DebtTitle title = loadVisible(titleId);
        TitleBalance balance = TitleBalance.of(ledger.findByTitleIdOrderByOccurredAtAscIdAsc(titleId));
        List<ComponentBalanceView> components = Arrays.stream(LedgerComponent.values()).map(c -> {
            TitleBalance.ComponentBalance b = balance.components().get(c);
            return new ComponentBalanceView(c.name(), b.charged().amount(), b.discounted().amount(), b.paid().amount(),
                    b.reversed().amount(), b.balance().amount());
        }).toList();
        List<TitleBalanceSnapshot> recent = snapshots.findTop12ByTitleIdOrderByReferenceDateDescCreatedAtDesc(titleId);
        Money divergence = recent.isEmpty() ? null : balance.openBalance().minus(recent.getFirst().getReportedBalance());
        return new TitleBalanceView(titleId, title.getStatus().name(), components, balance.openBalance().amount(),
                balance.paidTotal().amount(), balance.lastPaymentAt(), divergence == null ? null : divergence.amount(),
                recent.stream().map(s -> new BalanceSnapshotView(s.getReferenceDate(), s.getReportedBalance().amount(),
                        s.getImportBatchId(), s.getCreatedAt())).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public LedgerPage ledger(UUID titleId, PageQuery page) {
        loadVisible(titleId);
        return new LedgerPage(titleId, PageResponse.of(ledger.findByTitleId(titleId,
                page.toPageable(Sort.by("occurredAt", "id"))), DebtTitleService::entryView));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OwnershipView> ownershipHistory(UUID titleId) {
        loadVisible(titleId);
        return ownerships.findByTitleIdOrderByAssignedAtAsc(titleId).stream()
                .map(o -> new OwnershipView(o.getCreditorId(), o.getPortfolioId(), o.getAssignedAt(), o.getReleasedAt(),
                        o.getReleaseType() == null ? null : o.getReleaseType().name(), o.getReleaseReason()))
                .toList();
    }

    // ---------------------------------------------------------------- ciclo de vida

    @Override
    @Transactional(readOnly = true)
    public ChangePage<TitleView> changes(UUID creditorId, UUID portfolioId, ChangeQuery query) {
        Guard.require(creditorId != null, "title.creditor_required", "Credor obrigatório");
        accessScope.requireVisible("creditor", creditorId, creditorId);
        LocalDate today = DebtViews.today(clock);
        List<DebtTitle> rows = titles.findChanges(creditorId, portfolioId, query);
        return ChangePage.of(rows, query.limit(), DebtTitle::getUpdatedAt, DebtTitle::getId, DebtTitle::getVersion,
                t -> DebtViews.title(t, today));
    }

    @Override
    public TitleView suspend(UUID titleId, String reason, Long expectedVersion) {
        return transition(titleId, expectedVersion, t -> t.suspend(reason), "debt_title.suspended");
    }

    @Override
    public TitleView reactivate(UUID titleId, Long expectedVersion) {
        return transition(titleId, expectedVersion, DebtTitle::reactivate, "debt_title.reactivated");
    }

    @Override
    public TitleView cancel(UUID titleId, String reason, Long expectedVersion) {
        return transition(titleId, expectedVersion, t -> t.cancel(reason), "debt_title.cancelled");
    }

    @Override
    public List<TitleView> returnToCreditor(Collection<UUID> titleIds, String reason) {
        Instant now = Instant.now(clock);
        return lockAllForCommand(titleIds).stream().map(title -> returnLocked(title, reason, now)).toList();
    }

    @Override
    public TitleView returnToCreditor(UUID titleId, String reason, Long expectedVersion) {
        DebtTitle title = lockForCommand(titleId);
        requireVersion(title, expectedVersion);
        return returnLocked(title, reason, Instant.now(clock));
    }

    private TitleView returnLocked(DebtTitle title, String reason, Instant now) {
        TitleStatus previous = title.returnToCreditor(reason);
        releaseOwnership(title, now, OwnershipReleaseType.RETURN, title.getStatusReason());
        titles.save(title);
        auditTrail.record(AuditEntry.of("debt_title.returned", "debt_title", title.getId())
                .creditor(title.getCreditorId()).reason(title.getStatusReason())
                .change("status", previous, title.getStatus())
                .meta("portfolioId", title.getPortfolioId()));
        notifier.changed(title, previous, title.getOpenBalance(), Trigger.RETURN);
        return view(title);
    }

    /** Concorrência otimista da API pública ({@code If-Match}): conferida sob o lock do título. */
    private static void requireVersion(DebtTitle title, Long expectedVersion) {
        if (expectedVersion != null && !expectedVersion.equals(title.getVersion())) {
            throw new PreconditionFailedException("debt_title.version_mismatch",
                    "O título foi alterado depois da leitura. Consulte-o de novo e repita a operação.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> debtorIdsByImportBatch(UUID creditorId, UUID importBatchId, UUID afterDebtorId, int limit) {
        Guard.require(creditorId != null && importBatchId != null, "title.import_batch_required",
                "Credor e lote de importação obrigatórios");
        Guard.require(limit >= 1 && limit <= 1000, "title.batch_limit_invalid", "Tamanho do lote entre 1 e 1000");
        accessScope.requireCreditor(creditorId);
        return titles.findDebtorIdsByImportBatch(creditorId, importBatchId, afterDebtorId, limit);
    }

    @Override
    public List<TitleView> transferPortfolio(Collection<UUID> titleIds, UUID targetPortfolioId, String reason) {
        Guard.require(targetPortfolioId != null, "title.portfolio_required", "Carteira de destino obrigatória");
        Guard.require(reason != null && !reason.isBlank(), "title.reason_required", "Informe o motivo da transferência");
        PortfolioView target = portfolios.requireAcceptingImports(targetPortfolioId);
        accessScope.requireCreditor(target.creditorId());
        Instant now = Instant.now(clock);
        List<DebtTitle> locked = lockAllForCommand(titleIds);
        locked.forEach(t -> Guard.require(t.getCreditorId().equals(target.creditorId()), "title.transfer_creditor_mismatch",
                "Transferência só é permitida entre carteiras do mesmo credor"));
        return locked.stream().map(title -> {
            UUID previousPortfolio = title.getPortfolioId();
            title.transferTo(targetPortfolioId);
            releaseOwnership(title, now, OwnershipReleaseType.TRANSFER, reason.trim());
            ownerships.save(new TitleOwnership(title.getId(), title.getCreditorId(), targetPortfolioId, now));
            titles.save(title);
            auditTrail.record(AuditEntry.of("debt_title.transferred", "debt_title", title.getId())
                    .creditor(title.getCreditorId()).reason(reason.trim())
                    .change("portfolioId", previousPortfolio, targetPortfolioId));
            notifier.changedAlways(title, title.getStatus(), title.getOpenBalance(), Trigger.TRANSFER);
            return view(title);
        }).toList();
    }

    private TitleView transition(UUID titleId, Long expectedVersion, Function<DebtTitle, TitleStatus> change,
                                 String auditAction) {
        DebtTitle title = lockForCommand(titleId);
        requireVersion(title, expectedVersion);
        TitleStatus previous = change.apply(title);
        titles.save(title);
        auditTrail.record(AuditEntry.of(auditAction, "debt_title", titleId)
                .creditor(title.getCreditorId()).reason(title.getStatusReason())
                .change("status", previous, title.getStatus()));
        notifier.changed(title, previous, title.getOpenBalance(), Trigger.MANUAL);
        return view(title);
    }

    /** Encerra o período aberto antes de abrir outro (índice único de período aberto por título). */
    private void releaseOwnership(DebtTitle title, Instant at, OwnershipReleaseType type, String reason) {
        TitleOwnership current = ownerships.findByTitleIdAndReleasedAtIsNull(title.getId())
                .orElseThrow(() -> new IllegalStateException("Título %s sem titularidade aberta".formatted(title.getId())));
        current.release(at, type, reason);
        ownerships.saveAndFlush(current);
    }

    // ---------------------------------------------------------------- escopo

    private DebtTitle loadVisible(UUID titleId) {
        DebtTitle title = titles.findById(titleId).orElseThrow(() -> ResourceNotFoundException.of("debt_title", titleId));
        accessScope.requireVisible("debt_title", titleId, title.getCreditorId());
        return title;
    }

    private DebtTitle lockForCommand(UUID titleId) {
        DebtTitle title = titles.findByIdForUpdate(titleId).orElseThrow(() -> ResourceNotFoundException.of("debt_title", titleId));
        accessScope.requireVisible("debt_title", titleId, title.getCreditorId());
        accessScope.requireCreditor(title.getCreditorId());
        return title;
    }

    private List<DebtTitle> lockAllForCommand(Collection<UUID> titleIds) {
        Guard.require(titleIds != null && !titleIds.isEmpty(), "title.required", "Informe ao menos um título");
        Set<UUID> ids = new LinkedHashSet<>(titleIds);
        if (ids.size() > MAX_BATCH) {
            throw new BusinessRuleException("title.batch_too_large", "Informe no máximo %d títulos por operação".formatted(MAX_BATCH));
        }
        List<DebtTitle> locked = titles.findAllByIdInForUpdate(ids);
        for (UUID id : ids) {
            DebtTitle title = locked.stream().filter(t -> t.getId().equals(id)).findFirst()
                    .orElseThrow(() -> ResourceNotFoundException.of("debt_title", id));
            accessScope.requireVisible("debt_title", id, title.getCreditorId());
            accessScope.requireCreditor(title.getCreditorId());
        }
        return locked;
    }

    private TitleView view(DebtTitle title) {
        return DebtViews.title(title, DebtViews.today(clock));
    }

    private static LedgerEntryView entryView(TitleLedgerEntry e) {
        return new LedgerEntryView(e.getId(), e.getEntryType().name(), e.getComponent().name(), e.getAmount().amount(),
                e.getOccurredAt(), e.getReferenceType(), e.getReferenceId(), e.getReason(), e.getCreatedAt());
    }

    private static LocalDate earliest(LocalDate a, LocalDate b) {
        return a == null || b.isBefore(a) ? b : a;
    }

    private static LocalDate latest(LocalDate a, LocalDate b) {
        return a == null || b.isAfter(a) ? b : a;
    }
}
