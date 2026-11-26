package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.application.port.in.CreditorErasureNoticeStatusQueries;
import br.com.gestorcobranca.compliance.application.port.in.CreditorErasureNoticeUseCases;
import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice;
import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice.Channel;
import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice.Item;
import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice.Origin;
import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice.Status;
import br.com.gestorcobranca.compliance.domain.repository.CreditorErasureNoticeRepository;
import br.com.gestorcobranca.compliance.domain.repository.PendingNoticeCount;
import br.com.gestorcobranca.compliance.domain.service.CreditorErasureNoticeText;
import br.com.gestorcobranca.compliance.domain.service.CreditorErasureNoticeText.Subject;
import br.com.gestorcobranca.compliance.domain.service.RemittanceCut;
import br.com.gestorcobranca.debt.application.port.in.AnonymizedSubjectQueries;
import br.com.gestorcobranca.debt.application.port.in.AnonymizedSubjectQueries.TitleReference;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorView;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.PortfolioView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Avisos ao credor da anonimização do titular (decisões 1478–1481). {@link #openFor} é chamado só pelo
 * {@link ErasureGate}, na transação da anonimização; o restante é a fila da equipe de atendimento ao titular.
 */
@Service
@Transactional
class CreditorErasureNoticeService implements CreditorErasureNoticeUseCases, CreditorErasureNoticeStatusQueries {

    private static final String RESOURCE = "creditor_erasure_notice";
    private static final Sort SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
    private static final Comparator<TitleReference> ITEM_ORDER = Comparator
            .comparing((TitleReference t) -> t.portfolioId().toString())
            .thenComparing(TitleReference::displayReference)
            .thenComparing(TitleReference::installmentNumber, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final CreditorErasureNoticeRepository notices;
    private final AnonymizedSubjectQueries anonymizedSubjects;
    private final CreditorUseCases creditors;
    private final PortfolioUseCases portfolios;
    private final UserDirectory users;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final Clock clock;

    CreditorErasureNoticeService(CreditorErasureNoticeRepository notices, AnonymizedSubjectQueries anonymizedSubjects,
                                 CreditorUseCases creditors, PortfolioUseCases portfolios, UserDirectory users,
                                 AccessScope accessScope, CurrentActor currentActor, AuditTrail auditTrail,
                                 BusinessEventLogger businessEvents, Clock clock) {
        this.notices = notices;
        this.anonymizedSubjects = anonymizedSubjects;
        this.creditors = creditors;
        this.portfolios = portfolios;
        this.users = users;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.clock = clock;
    }

    /**
     * Um aviso por credor com títulos conservados do titular recém-anonimizado (credor sem título não é avisado: não
     * tem o que eliminar nem o que mandar de novo na carga). Na transação do chamador: se a anonimização for desfeita,
     * o aviso também é. Coobrigado sem título próprio não entra (resíduo da decisão 1480).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<CreditorErasureNotice> openFor(UUID debtorId, Origin origin, UUID originId, Instant erasedAt) {
        Map<UUID, List<TitleReference>> byCreditor = anonymizedSubjects.referencesOf(debtorId).stream()
                .collect(Collectors.groupingBy(TitleReference::creditorId, LinkedHashMap::new, Collectors.toList()));
        return byCreditor.entrySet().stream().map(e -> {
            List<Item> items = e.getValue().stream().sorted(ITEM_ORDER)
                    .map(t -> new Item(t.portfolioId(), t.displayReference(), t.installmentNumber()))
                    .toList();
            CreditorErasureNotice notice = notices.save(new CreditorErasureNotice(debtorId, e.getKey(), origin,
                    originId, erasedAt, items));
            auditTrail.record(AuditEntry.of("creditor_erasure_notice.opened", RESOURCE, notice.getId())
                    .created("status", notice.getStatus())
                    .meta("debtorId", debtorId)
                    .meta("creditorId", notice.getCreditorId())
                    .meta("origin", origin)
                    .meta("originId", originId)
                    .meta("titles", items.size()));
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.CREDITOR_ERASURE_NOTICE_OPENED)
                    .creditor(notice.getCreditorId())
                    .reference(RESOURCE, notice.getId())
                    .attr("origin", origin)
                    .attr("titles", items.size()));
            return notice;
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NoticeSummary> search(NoticeSearch search, PageQuery page) {
        requireAccess();
        Status status = ComplianceEnums.parseOptional(Status.class, search == null ? null : search.status(),
                "creditor_erasure_notice.status_invalid", "Situação");
        Page<CreditorErasureNotice> found = notices.search(status, search == null ? null : search.creditorId(),
                search == null ? null : search.originId(), page.toPageable(SORT));
        Map<UUID, String> creditorNames = creditorNames(found.getContent().stream()
                .map(CreditorErasureNotice::getCreditorId).distinct().toList());
        return PageResponse.of(found, n -> summary(n, creditorNames));
    }

    @Override
    @Transactional(readOnly = true)
    public NoticeView get(UUID id) {
        requireAccess();
        return view(notices.findById(id).orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, id)));
    }

    @Override
    public NoticeView registerNotified(UUID id, RegisterNotifiedCommand command) {
        requireAccess();
        CreditorErasureNotice notice = notices.findByIdForUpdate(id)
                .orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, id));
        markNotified(notice, command.notifiedAt(), channel(command.channel()), null);
        return view(notice);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> noticeFor(UUID debtorId, UUID creditorId) {
        accessScope.requirePermission(Permission.IMPORT_EXECUTE);
        accessScope.requireCreditor(creditorId);
        return notices.findIdByDebtorIdAndCreditorId(debtorId, creditorId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CreditorPending> pendingByCreditor() {
        requireAccess();
        List<PendingNoticeCount> counts = notices.pendingByCreditor();
        Map<UUID, String> names = creditorNames(counts.stream().map(PendingNoticeCount::creditorId).toList());
        return counts.stream().map(c -> new CreditorPending(c.creditorId(), names.get(c.creditorId()), c.pending(),
                c.oldestErasedAt())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RemittanceView remittance(UUID creditorId) {
        requireAccess();
        String creditorName = creditorNames(List.of(creditorId)).get(creditorId);
        if (creditorName == null) {
            throw ResourceNotFoundException.of("creditor", creditorId);
        }
        List<CreditorErasureNotice> found = notices.findPendingByCreditor(creditorId, MAX_REMITTANCE);
        if (found.isEmpty()) {
            return new RemittanceView(creditorId, creditorName, List.of(), 0, 0, null, null);
        }
        List<CreditorErasureNotice> pending = RemittanceCut.take(found, MAX_REMITTANCE_TITLES);
        long total = found.size() < MAX_REMITTANCE ? found.size()
                : notices.countByCreditorIdAndStatus(creditorId, Status.PENDING);
        Map<UUID, String> portfolioNames = portfolioNames(pending.stream().flatMap(n -> n.getItems().stream()).toList());
        List<Subject> subjects = pending.stream().map(n -> new Subject(LocalDate.ofInstant(n.getErasedAt(),
                TimeConfig.BUSINESS_ZONE), n.getItems())).toList();
        return new RemittanceView(creditorId, creditorName, pending.stream().map(CreditorErasureNotice::getId).toList(),
                subjects.stream().mapToInt(x -> x.items().size()).sum(), total,
                pending.stream().map(CreditorErasureNotice::getErasedAt).max(Instant::compareTo).orElseThrow(),
                CreditorErasureNoticeText.compose(creditorName, portfolioNames, subjects));
    }

    @Override
    public List<NoticeSummary> registerNotifiedBatch(RegisterBatchCommand command) {
        requireAccess();
        Set<UUID> ids = command.noticeIds() == null ? Set.of()
                : command.noticeIds().stream().filter(Objects::nonNull).collect(Collectors.toSet());
        Guard.require(!ids.isEmpty() && ids.size() <= MAX_REMITTANCE, "creditor_erasure_notice.batch_size_invalid",
                "Informe de 1 a %d avisos".formatted(MAX_REMITTANCE));
        Channel channel = channel(command.channel());
        List<CreditorErasureNotice> locked = notices.findAllByIdInForUpdate(ids);
        if (locked.size() != ids.size()) {
            UUID missing = ids.stream().filter(id -> locked.stream().noneMatch(n -> n.getId().equals(id)))
                    .findFirst().orElseThrow();
            throw ResourceNotFoundException.of(RESOURCE, missing);
        }
        Guard.require(locked.stream().map(CreditorErasureNotice::getCreditorId).distinct().count() == 1,
                "creditor_erasure_notice.batch_mixed_creditors", "A remessa precisa ser de um credor só");
        locked.forEach(n -> markNotified(n, command.notifiedAt(), channel, locked.size()));
        Map<UUID, String> names = creditorNames(List.of(locked.getFirst().getCreditorId()));
        return locked.stream().map(n -> summary(n, names)).toList();
    }

    private Channel channel(String value) {
        return ComplianceEnums.parse(Channel.class, value, "creditor_erasure_notice.channel_invalid", "Canal do aviso");
    }

    /** @param batch tamanho da remessa registrada de uma vez (nulo = registro individual) */
    private void markNotified(CreditorErasureNotice notice, Instant notifiedAt, Channel channel, Integer batch) {
        notice.registerNotified(notifiedAt, channel, currentActor.get().id(), Instant.now(clock));
        notices.save(notice);
        AuditEntry audit = AuditEntry.of("creditor_erasure_notice.notified", RESOURCE, notice.getId())
                .change("status", Status.PENDING, notice.getStatus())
                .change("notifiedAt", null, notice.getNotifiedAt())
                .change("notifiedChannel", null, channel)
                .meta("creditorId", notice.getCreditorId());
        BusinessEvent event = BusinessEvent.of(BusinessEventTypes.CREDITOR_ERASURE_NOTICE_NOTIFIED)
                .creditor(notice.getCreditorId())
                .reference(RESOURCE, notice.getId())
                .channel(channel.name())
                .durationMs(Duration.between(notice.getErasedAt(), notice.getNotifiedAt()).toMillis());
        if (batch != null) {
            audit.meta("batch", batch);
            event.attr("batch", batch);
        }
        auditTrail.record(audit);
        businessEvents.log(event);
    }

    private static NoticeSummary summary(CreditorErasureNotice n, Map<UUID, String> creditorNames) {
        return new NoticeSummary(n.getId(), n.getDebtorId(), n.getCreditorId(), creditorNames.get(n.getCreditorId()),
                n.getOrigin().name(), n.getOriginId(), n.getErasedAt(), n.getStatus().name(), n.getNotifiedAt(),
                n.getNotifiedChannel() == null ? null : n.getNotifiedChannel().name());
    }

    private Map<UUID, String> portfolioNames(List<Item> items) {
        List<UUID> ids = items.stream().map(Item::getPortfolioId).distinct().toList();
        return ids.isEmpty() ? Map.of() : portfolios.getAll(ids).stream()
                .collect(Collectors.toMap(PortfolioView::id, PortfolioView::name, (a, b) -> a));
    }

    private void requireAccess() {
        accessScope.requirePermission(Permission.DATA_SUBJECT_REQUEST_MANAGE);
        accessScope.requireAllCreditors(RESOURCE);
    }

    private NoticeView view(CreditorErasureNotice n) {
        List<Item> items = n.getItems();
        Map<UUID, String> portfolioNames = portfolioNames(items);
        String creditorName = creditorNames(List.of(n.getCreditorId())).get(n.getCreditorId());
        String notifiedByName = n.getNotifiedBy() == null ? null
                : users.find(n.getNotifiedBy()).map(UserSummary::name).orElse(null);
        LocalDate erasedOn = LocalDate.ofInstant(n.getErasedAt(), TimeConfig.BUSINESS_ZONE);
        String message = CreditorErasureNoticeText.compose(Objects.requireNonNullElse(creditorName, "credor"),
                portfolioNames, erasedOn, items);
        return new NoticeView(n.getId(), n.getDebtorId(), n.getCreditorId(), creditorName, n.getOrigin().name(),
                n.getOriginId(), n.getErasedAt(), n.getStatus().name(), n.getNotifiedAt(),
                n.getNotifiedChannel() == null ? null : n.getNotifiedChannel().name(), n.getNotifiedBy(),
                notifiedByName, n.getRegisteredAt(),
                items.stream().map(i -> new NoticeItemView(i.getPortfolioId(), portfolioNames.get(i.getPortfolioId()),
                        i.getReference(), i.getInstallmentNumber())).toList(),
                message);
    }

    private Map<UUID, String> creditorNames(List<UUID> creditorIds) {
        if (creditorIds.isEmpty()) {
            return Map.of();
        }
        return creditors.getAll(creditorIds).stream().collect(Collectors.toMap(CreditorView::id,
                c -> c.tradeName() == null || c.tradeName().isBlank() ? c.legalName() : c.tradeName(), (a, b) -> a,
                LinkedHashMap::new));
    }

}
