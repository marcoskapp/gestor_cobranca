package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.DebtProperties;
import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases;
import br.com.gestorcobranca.debt.application.port.in.reversalhold.ReversalHoldPlacement;
import br.com.gestorcobranca.debt.application.service.TitleChangeNotifier.Trigger;
import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.model.ReversalHold;
import br.com.gestorcobranca.debt.domain.model.ReversalHold.ItemKind;
import br.com.gestorcobranca.debt.domain.model.ReversalHoldReleaseReason;
import br.com.gestorcobranca.debt.domain.model.ReversalHoldState;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.debt.domain.repository.DebtorRepository;
import br.com.gestorcobranca.debt.domain.repository.ReversalHoldRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Retenção da cobrança após estorno de pagamento recebido pelo credor (H07 parte 3). Ordem das travas em todos os
 * caminhos: devedor (compartilhada, decisão 1602) → título → retenção. A liberação toma a mesma trava do devedor antes
 * do {@code FOR UPDATE} da retenção e reconfere tudo sob a trava.
 */
@Service
@Transactional
class ReversalHoldService implements ReversalHoldUseCases, ReversalHoldPlacement {

    private static final Logger LOG = LoggerFactory.getLogger(ReversalHoldService.class);
    static final String RESOURCE = "reversal_hold";
    private static final Instant NO_START = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant NO_END = Instant.parse("9999-12-31T00:00:00Z");

    private final ReversalHoldRepository holds;
    private final DebtorRepository debtors;
    private final DebtTitleRepository titles;
    private final DebtorVisibility debtorVisibility;
    private final TitleChangeNotifier notifier;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final DebtMetrics metrics;
    private final DebtProperties properties;
    private final Clock clock;

    ReversalHoldService(ReversalHoldRepository holds, DebtorRepository debtors, DebtTitleRepository titles,
                        DebtorVisibility debtorVisibility, TitleChangeNotifier notifier, AuditTrail auditTrail,
                        BusinessEventLogger businessEvents, AccessScope accessScope, CurrentActor currentActor,
                        DebtMetrics metrics, DebtProperties properties, Clock clock) {
        this.holds = holds;
        this.debtors = debtors;
        this.titles = titles;
        this.debtorVisibility = debtorVisibility;
        this.notifier = notifier;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ criação (payment, mesma transação)

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> place(ReversalHoldPlacement.PlaceCommand c) {
        Guard.require(c != null && c.creditorId() != null && c.debtorId() != null, "reversal_hold.parties_required",
                "Credor e devedor obrigatórios");
        accessScope.requireCreditor(c.creditorId());
        Debtor debtor = debtors.findById(c.debtorId())
                .orElseThrow(() -> ResourceNotFoundException.of("debtor", c.debtorId()));
        debtors.refreshForShare(debtor);
        if (debtor.isAnonymized()) {
            // titular eliminado (decisão 1603): o título fica quitado, sem cobrança nem pendência
            metrics.reversalHold("place", "skipped_anonymized");
            LOG.atInfo().addKeyValue("paymentId", c.paymentId()).log("Estorno de titular eliminado: sem retenção");
            return Optional.empty();
        }
        if (holds.existsByReversalId(c.reversalId())) {
            throw new ConflictException("reversal_hold.duplicate", "Este estorno já tem retenção");
        }
        Actor actor = currentActor.get();
        String placedByType = actor.type() == Actor.ActorType.USER ? "USER" : "SYSTEM";
        Instant now = Instant.now(clock);
        ReversalHold hold = holds.saveAndFlush(new ReversalHold(c.creditorId(), c.debtorId(), c.paymentId(),
                c.reversalId(), c.agreementIds(), c.reversalReason(), c.titleIds(), c.installmentIds(),
                c.allocationIds(), actor.id(), placedByType, c.informedBy(), now));
        auditTrail.record(AuditEntry.of("reversal_hold.placed", RESOURCE, hold.getId())
                .creditor(hold.getCreditorId())
                .created("state", hold.getState())
                .created("paymentId", hold.getPaymentId())
                .created("reversalId", hold.getReversalId())
                .created("agreements", hold.ids(ItemKind.AGREEMENT).size())
                .meta("titles", hold.ids(ItemKind.TITLE).size())
                .meta("installments", hold.ids(ItemKind.INSTALLMENT).size())
                .meta("placedByType", placedByType));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.REVERSAL_HOLD_PLACED).creditor(hold.getCreditorId())
                .debtor(hold.getDebtorId()).reference(RESOURCE, hold.getId())
                .attr("reversalReason", hold.getReversalReason()).attr("placedByType", placedByType)
                .attr("titles", hold.ids(ItemKind.TITLE).size()).attr("agreements", hold.ids(ItemKind.AGREEMENT).size()));
        metrics.reversalHold("place", "placed");
        // títulos do acordo podem não mudar de status no estorno: a elegibilidade relê a retenção
        notifyTitles(hold);
        return Optional.of(hold.getId());
    }

    // ------------------------------------------------------------------ consultas internas

    @Override
    @Transactional(readOnly = true)
    public boolean isHeld(UUID debtorId, UUID creditorId) {
        return debtorId != null && creditorId != null && holds.existsPendingForContact(creditorId, debtorId);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> heldTitleIds(UUID creditorId, UUID debtorId) {
        return creditorId == null || debtorId == null ? Set.of() : Set.copyOf(holds.findPendingTitleIds(creditorId, debtorId));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> heldAmong(Collection<UUID> titleIds) {
        return Set.copyOf(holds.findPendingTitleIdsIn(titleIds));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> pendingInstallmentIds(UUID agreementId) {
        return agreementId == null ? Set.of() : Set.copyOf(holds.findPendingInstallmentIds(agreementId));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> breakExemptInstallmentIds(UUID agreementId, LocalDate today, int graceDays) {
        if (agreementId == null) {
            return Set.of();
        }
        // liberada há menos de graceDays dias: conta a carência a partir do dia da liberação
        Instant since = today.minusDays(Math.max(0, graceDays)).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        return Set.copyOf(holds.findBreakExemptInstallmentIds(agreementId, since));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean createdForAllocation(UUID allocationId) {
        return allocationId != null && holds.existsByAllocationId(allocationId);
    }

    // ------------------------------------------------------------------ telas

    @Override
    @Transactional(readOnly = true)
    public PageResponse<HoldView> search(HoldFilter filter, PageQuery page) {
        requireInternal();
        accessScope.requirePermission(Permission.PAYMENT_VIEW);
        Guard.require(filter != null, "reversal_hold.filter_required", "Filtro obrigatório");
        String state = filter.state() == null || filter.state().isBlank() ? ReversalHoldState.PENDING.name()
                : parseState(filter.state());
        // pendentes: todas, sem período (a pendência não vence — UX-02); liberadas e encerradas: período obrigatório
        boolean pending = ReversalHoldState.PENDING.name().equals(state);
        Instant from = NO_START;
        Instant to = NO_END;
        if (!pending) {
            Guard.require(filter.from() != null && filter.to() != null,
                    "reversal_hold.period_required", "Informe o período (de e até)");
            Guard.require(!filter.to().isBefore(filter.from())
                            && ChronoUnit.DAYS.between(filter.from(), filter.to()) <= MAX_PERIOD_DAYS,
                    "reversal_hold.period_invalid",
                    "Período inválido: a data final não pode ser anterior à inicial nem passar de %d dias"
                            .formatted(MAX_PERIOD_DAYS));
            from = filter.from().atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
            to = filter.to().plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        }
        PageQuery p = page == null ? PageQuery.of(null, null) : page;
        Guard.require(p.size() <= MAX_PAGE_SIZE, "reversal_hold.page_size_invalid",
                "Até %d linhas por página".formatted(MAX_PAGE_SIZE));
        if (filter.creditorId() != null) {
            accessScope.requireVisible("creditor", filter.creditorId(), filter.creditorId());
        }
        boolean all = accessScope.hasAllCreditors();
        Set<UUID> scope = all ? Set.of() : accessScope.restrictCreditors(List.of());
        if (!all && scope.isEmpty()) {
            return new PageResponse<>(List.of(), p.page(), p.size(), 0, 0);
        }
        // o pagamento é só condição dentro do escopo (pagamento de outro credor: lista vazia)
        Page<ReversalHold> result = holds.search(scope, all, filter.creditorId(), filter.paymentId(), state, from, to,
                PageRequest.of(p.page(), p.size(), Sort.by(Sort.Order.asc("placedAt"), Sort.Order.asc("id"))));
        return PageResponse.of(result, views(result.getContent()));
    }

    @Override
    @Transactional(readOnly = true)
    public HoldView get(UUID holdId) {
        requireInternal();
        accessScope.requirePermission(Permission.PAYMENT_VIEW);
        ReversalHold hold = holds.findById(holdId).orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, holdId));
        accessScope.requireVisible(RESOURCE, holdId, hold.getCreditorId());
        return views(List.of(hold)).apply(hold);
    }

    @Override
    @Transactional(readOnly = true)
    public List<HoldView> forPayment(UUID paymentId) {
        requireInternal();
        accessScope.requirePermission(Permission.PAYMENT_VIEW);
        List<ReversalHold> found = holds.findByPaymentIdOrderByPlacedAtDesc(paymentId).stream()
                .filter(h -> accessScope.canAccessCreditor(h.getCreditorId())).toList();
        Function<ReversalHold, HoldView> view = views(found);
        return found.stream().map(view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<HeldTitle> heldTitlesOfDebtor(UUID debtorId) {
        requireInternal();
        Actor actor = currentActor.get();
        if (!actor.has(Permission.DEBTOR_VIEW)) {
            accessScope.requirePermission(Permission.CASE_VIEW);
        }
        // mesma visibilidade do devedor da lista de títulos (404 + log de acesso fora do escopo; H3-03)
        debtorVisibility.requireVisible(debtorId);
        boolean payments = actor.has(Permission.PAYMENT_VIEW);
        List<HeldTitle> result = new ArrayList<>();
        for (ReversalHold h : holds.findByDebtorIdAndState(debtorId, ReversalHoldState.PENDING)) {
            if (!accessScope.canAccessCreditor(h.getCreditorId())) {
                continue;
            }
            for (UUID titleId : h.ids(ItemKind.TITLE)) {
                result.add(new HeldTitle(titleId, h.getId(), payments ? h.getPaymentId() : null, h.getPlacedAt()));
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ liberação

    @Override
    public HoldView release(UUID holdId, ReleaseCommand command) {
        requireInternal();
        Actor actor = currentActor.get();
        if (actor.type() != Actor.ActorType.USER) {
            throw accessScope.denyActorType(RESOURCE);
        }
        accessScope.requirePermission(Permission.PAYMENT_REVIEW);
        // escopo antes de qualquer regra e de qualquer trava: fora do escopo, de outra assessoria (RLS) ou inexistente
        // dão o mesmo 404 (RS-117)
        ReversalHoldRepository.Parties parties = holds.findPartiesById(holdId)
                .orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, holdId));
        accessScope.requireVisible(RESOURCE, holdId, parties.getCreditorId());
        Guard.require(command != null, "reversal_hold.release_required", "Dados da liberação obrigatórios");
        // ordem das travas: devedor (compartilhada) → retenção
        debtors.findById(parties.getDebtorId()).ifPresent(debtors::refreshForShare);
        ReversalHold hold = holds.findByIdForUpdate(holdId).orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, holdId));
        accessScope.requireVisible(RESOURCE, holdId, hold.getCreditorId());
        accessScope.requireCreditor(hold.getCreditorId());
        Instant now = Instant.now(clock);
        ReversalHoldState previous = hold.getState();
        ReversalHoldReleaseReason reason;
        try {
            hold.requireVersion(command.expectedVersion());
            reason = parseReason(command.reasonCode());
            hold.release(actor.id(), reason, command.note(), command.confirmedWithCreditor(), now);
        } catch (RuntimeException e) {
            metrics.reversalHold("release", refusal(e));
            throw e;
        }
        holds.save(hold);
        auditTrail.record(AuditEntry.of("reversal_hold.released", RESOURCE, hold.getId())
                .creditor(hold.getCreditorId())
                .change("state", previous, hold.getState())
                .created("releaseReason", reason)
                .created("confirmedWithCreditor", true)
                .meta("paymentId", hold.getPaymentId())
                .meta("reversalId", hold.getReversalId())
                .meta("noteLength", hold.getReleaseNote().length()));
        Duration age = Duration.between(hold.getPlacedAt(), now);
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.REVERSAL_HOLD_RELEASED).creditor(hold.getCreditorId())
                .debtor(hold.getDebtorId()).reference(RESOURCE, hold.getId())
                .attr("releaseReason", reason).attr("ageHours", age.toHours())
                .attr("reversalReason", hold.getReversalReason()));
        metrics.reversalHold("release", "released");
        metrics.reversalHoldReleasedAge(age);
        // a cobrança volta (fila, régua, portal) pelo caminho normal: o collection relê a retenção e o porteiro decide
        notifyTitles(hold);
        return views(List.of(hold)).apply(hold);
    }

    // ------------------------------------------------------------------ anonimização (consumidor de evento)

    /** Encerra as retenções pendentes do titular anonimizado e limpa as justificativas (RS-122). */
    void endForAnonymizedDebtor(UUID debtorId) {
        Instant now = Instant.now(clock);
        for (ReversalHold hold : holds.findByDebtorId(debtorId)) {
            ReversalHoldState previous = hold.getState();
            boolean hadNote = hold.getReleaseNote() != null;
            if (!hold.endByAnonymization(now)) {
                continue;
            }
            holds.save(hold);
            AuditEntry entry = AuditEntry.of("reversal_hold.ended", RESOURCE, hold.getId())
                    .creditor(hold.getCreditorId())
                    .meta("reason", "DEBTOR_ANONYMIZED")
                    .meta("releaseNoteCleared", hadNote);
            if (previous != hold.getState()) {
                entry.change("state", previous, hold.getState());
                businessEvents.log(BusinessEvent.of(BusinessEventTypes.REVERSAL_HOLD_ENDED)
                        .creditor(hold.getCreditorId()).reference(RESOURCE, hold.getId())
                        .attr("reason", "DEBTOR_ANONYMIZED"));
                metrics.reversalHold("end", "ended");
            }
            auditTrail.record(entry);
            notifyTitles(hold);
        }
    }

    // ------------------------------------------------------------------ apoio

    /**
     * Títulos retidos e, no mesmo credor, os títulos próprios de cada coobrigado ativo dos contratos retidos (RC-02): o
     * collection reavalia também o caso do coobrigado, que o porteiro passa a negar ou liberar.
     */
    private void notifyTitles(ReversalHold hold) {
        List<UUID> held = hold.ids(ItemKind.TITLE);
        if (held.isEmpty()) {
            return;
        }
        Set<UUID> ids = new LinkedHashSet<>(held);
        // um título por coobrigado no credor (prefere um em cobrança): o collection reavalia o caso dele (RC2-04)
        ids.addAll(holds.findCoobligorTitleIds(held));
        for (DebtTitle title : titles.findAllByIdIn(ids)) {
            notifier.changedAlways(title, title.getStatus(), title.getOpenBalance(), Trigger.REVERSAL_HOLD);
        }
    }

    private Function<ReversalHold, HoldView> views(List<ReversalHold> page) {
        Actor actor = currentActor.get();
        boolean debtorView = actor.has(Permission.DEBTOR_VIEW);
        boolean titleView = debtorView || actor.has(Permission.CASE_VIEW);
        boolean agreementView = actor.has(Permission.AGREEMENT_VIEW);
        boolean reviewer = actor.has(Permission.PAYMENT_REVIEW) && actor.type() == Actor.ActorType.USER;
        Map<UUID, Debtor> debtorById = page.isEmpty() ? Map.of() : debtors.findAllByIdIn(
                        page.stream().map(ReversalHold::getDebtorId).collect(Collectors.toCollection(LinkedHashSet::new)))
                .stream().collect(Collectors.toMap(Debtor::getId, Function.identity()));
        Set<UUID> creditorIds = page.stream().map(ReversalHold::getCreditorId).collect(Collectors.toSet());
        Map<UUID, Long> inWindow = creditorIds.isEmpty() ? Map.of() : holds.countByCreditorSince(creditorIds,
                        Instant.now(clock).minus(properties.reversalHold().window())).stream()
                .collect(Collectors.toMap(ReversalHoldRepository.CreditorCount::getCreditorId,
                        ReversalHoldRepository.CreditorCount::getTotal));
        return h -> {
            Debtor d = debtorById.get(h.getDebtorId());
            boolean anonymized = d != null && d.isAnonymized();
            String name = !debtorView || d == null ? null : anonymized ? Debtor.ANONYMIZED_NAME : d.getName();
            List<UUID> titleIds = h.ids(ItemKind.TITLE);
            boolean system = !"USER".equals(h.getPlacedByType());
            return new HoldView(h.getId(), h.getVersion() == null ? 0 : h.getVersion(), h.getCreditorId(),
                    h.getDebtorId(), name, anonymized, h.getPaymentId(), h.getReversalId(),
                    agreementView ? h.ids(ItemKind.AGREEMENT) : List.of(), titleView ? titleIds : List.of(), titleIds.size(),
                    h.ids(ItemKind.INSTALLMENT).size(), h.getReversalReason(), h.getState().name(), h.getPlacedAt(),
                    h.getPlacedByType(), system ? null : h.getPlacedBy(), h.getReleasedAt(), h.getReleasedBy(),
                    h.getReleaseReason() == null ? null : h.getReleaseReason().name(), h.getEndedAt(),
                    reviewer && h.getState() == ReversalHoldState.PENDING && !h.isAuthorOrInformer(actor.id()),
                    inWindow.getOrDefault(h.getCreditorId(), 0L));
        };
    }

    /** Só pessoas da assessoria: a pessoa do credor, a integração, o assistente e o devedor nunca veem a retenção. */
    private void requireInternal() {
        Actor actor = currentActor.get();
        if (actor.type() != Actor.ActorType.USER && actor.type() != Actor.ActorType.SYSTEM) {
            throw accessScope.denyActorType(RESOURCE);
        }
    }

    private static String parseState(String raw) {
        try {
            return ReversalHoldState.valueOf(raw.trim()).name();
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("reversal_hold.state_invalid", "Situação inválida");
        }
    }

    private static ReversalHoldReleaseReason parseReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ReversalHoldReleaseReason.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("reversal_hold.release_reason_invalid", "Motivo da liberação inválido");
        }
    }

    private static String refusal(RuntimeException e) {
        if (e instanceof DomainException d) {
            return switch (d.code()) {
                case "reversal_hold.self_release" -> "refused_self";
                case "reversal_hold.not_pending" -> "refused_state";
                case "reversal_hold.version_conflict" -> "refused_version";
                case "reversal_hold.confirmation_required" -> "refused_confirmation";
                case "reversal_hold.release_note_required" -> "refused_note";
                case "reversal_hold.release_reason_required", "reversal_hold.release_reason_invalid" -> "refused_reason";
                default -> "refused_other";
            };
        }
        return "failed";
    }
}
