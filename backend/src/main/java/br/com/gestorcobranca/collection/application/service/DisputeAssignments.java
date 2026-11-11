package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.AssigneeOption;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.RedistributionResult;
import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DisputeAssignment;
import br.com.gestorcobranca.collection.domain.model.DisputeAssignmentMode;
import br.com.gestorcobranca.collection.domain.model.DisputeDistributionMode;
import br.com.gestorcobranca.collection.domain.model.DisputePoolMember;
import br.com.gestorcobranca.collection.domain.model.DisputeSettings;
import br.com.gestorcobranca.collection.domain.model.DisputeStatus;
import br.com.gestorcobranca.collection.domain.repository.DisputeAssignmentRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputePoolMemberRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeSearchCriteria;
import br.com.gestorcobranca.collection.domain.repository.DisputeSettingsRepository;
import br.com.gestorcobranca.collection.domain.service.DisputeDistributor;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Responsável das contestações: atribuição manual, "pegar para mim", redistribuição e distribuição automática
 * opcional (analista elegível com menos contestações abertas, entre os analistas do credor ou, sem eles, os gerais).
 * Elegível = usuário ativo com {@code DISPUTE_WORK} ou {@code DISPUTE_MANAGE} e acesso ao credor. Toda mudança grava
 * o histórico (append-only), a auditoria e o evento de negócio {@code dispute.assigned}.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class DisputeAssignments {

    private static final Set<DisputeStatus> OPEN = Set.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW);

    private final DisputeRepository disputes;
    private final DisputeAssignmentRepository history;
    private final DisputePoolMemberRepository pool;
    private final DisputeSettingsRepository settings;
    private final UserDirectory users;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final CollectionMetrics metrics;
    private final Clock clock;

    DisputeAssignments(DisputeRepository disputes, DisputeAssignmentRepository history, DisputePoolMemberRepository pool,
                       DisputeSettingsRepository settings, UserDirectory users, CurrentActor currentActor,
                       AuditTrail auditTrail, BusinessEventLogger businessEvents, CollectionMetrics metrics, Clock clock) {
        this.disputes = disputes;
        this.history = history;
        this.pool = pool;
        this.settings = settings;
        this.users = users;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Muda o responsável (nulo = sem responsável), validando a elegibilidade do novo. */
    Dispute change(Dispute dispute, UUID assigneeId, DisputeAssignmentMode mode, String reason) {
        if (assigneeId != null) {
            requireEligible(assigneeId, dispute.getCreditorId());
        }
        Instant now = Instant.now(clock);
        UUID previous = dispute.assign(assigneeId, now);
        Dispute saved = disputes.save(dispute);
        UUID by = currentActor.actorId();
        history.save(new DisputeAssignment(saved.getId(), previous, assigneeId, mode, reason, by, now));
        auditTrail.record(AuditEntry.of("dispute.assigned", "dispute", saved.getId())
                .creditor(saved.getCreditorId())
                .reason(reason)
                .change("assigneeId", previous, assigneeId)
                .meta("mode", mode));
        businessEvents.log(DisputeEvents.base(BusinessEventTypes.DISPUTE_ASSIGNED, saved)
                .attr("mode", mode)
                .attr("hadAssignee", previous != null)
                .attr("assigned", assigneeId != null)
                .attr("status", saved.getStatus()));
        metrics.disputeAssignment(mode.name());
        return saved;
    }

    /** Distribuição automática na abertura, se configurada; sem analista elegível a contestação fica sem responsável. */
    void autoAssign(Dispute dispute) {
        DisputeSettings.Values values = settings.findFirstByOrderByCreatedAtAsc().map(DisputeSettings::values)
                .orElse(DisputeSettings.Values.DEFAULTS);
        if (values.distributionMode() != DisputeDistributionMode.LEAST_LOADED) {
            return;
        }
        leastLoaded(dispute.getCreditorId(), null).ifPresent(userId ->
                change(dispute, userId, DisputeAssignmentMode.AUTOMATIC, "Distribuição automática por menor carga"));
    }

    RedistributionResult redistribute(UUID from, UUID to, String reason, Set<UUID> creditorFilter) {
        Guard.require(from != null, "dispute.redistribution_from_required", "Informe de quem são as contestações");
        Guard.require(!from.equals(to), "dispute.redistribution_same", "Origem e destino são o mesmo analista");
        Guard.require(reason != null && !reason.isBlank() && reason.trim().length() <= 500,
                "dispute.redistribution_reason_required", "Motivo obrigatório (até 500 caracteres)");
        if (to != null) {
            // mesma regra da lista de destinos (CaseAssigneeService, DISPUTE_REDISTRIBUTION_TARGET)
            Set<UUID> creditors = disputes.findOpenCreditorIdsByAssignee(from, creditorFilter);
            UserSummary target = users.find(to).orElseThrow(() -> new BusinessRuleException("dispute.assignee_invalid",
                    "Analista inexistente"));
            Guard.require(isEligibleForAll(target, creditors), "dispute.assignee_invalid",
                    "O analista de destino precisa estar ativo, trabalhar contestações e ter acesso aos credores de todas "
                            + "as contestações em aberto da origem");
        }
        List<Dispute> open = disputes.search(new DisputeSearchCriteria(creditorFilter, null, OPEN, null, null, from,
                false, null, null, null, false, false, null), PageRequest.of(0, 1000, Sort.by("internalDueDate", "id")))
                .getContent();
        int moved = 0;
        int unassigned = 0;
        for (Dispute d : open) {
            Optional<UUID> target = to != null ? Optional.of(to) : leastLoaded(d.getCreditorId(), from);
            if (target.isPresent()) {
                change(d, target.get(), DisputeAssignmentMode.REDISTRIBUTION, reason);
                moved++;
            } else {
                change(d, null, DisputeAssignmentMode.RELEASE, reason);
                unassigned++;
            }
        }
        return new RedistributionResult(moved, unassigned);
    }

    /** Analistas elegíveis para o credor com a carga atual, do menos carregado ao mais carregado. */
    List<AssigneeOption> options(UUID creditorId) {
        List<UserSummary> eligible = eligibleUsers(creditorId);
        Map<UUID, Long> counts = disputes.countOpenByAssignees(eligible.stream().map(UserSummary::id).toList());
        return eligible.stream()
                .map(u -> new AssigneeOption(u.id(), u.name(), counts.getOrDefault(u.id(), 0L)))
                .sorted(Comparator.comparingLong(AssigneeOption::openDisputes).thenComparing(AssigneeOption::name))
                .toList();
    }

    /** Analista do grupo do credor (ou, sem ele, do grupo geral) com menos contestações abertas. */
    Optional<UUID> leastLoaded(UUID creditorId, UUID excluded) {
        List<DisputePoolMember> members = pool.findByActiveTrue();
        Set<UUID> ofCreditor = members.stream().filter(m -> creditorId.equals(m.getCreditorId()))
                .map(DisputePoolMember::getUserId).collect(Collectors.toSet());
        Set<UUID> candidates = !ofCreditor.isEmpty() ? ofCreditor : members.stream()
                .filter(m -> m.getCreditorId() == null).map(DisputePoolMember::getUserId).collect(Collectors.toSet());
        if (excluded != null) {
            candidates.remove(excluded);
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Set<UUID> eligible = users.findAll(candidates).stream()
                .filter(u -> isEligible(u, creditorId)).map(UserSummary::id).collect(Collectors.toSet());
        return DisputeDistributor.pick(eligible, disputes.countOpenByAssignees(eligible), history.lastAssignedAt(eligible));
    }

    void requireEligible(UUID userId, UUID creditorId) {
        UserSummary user = users.find(userId).orElseThrow(() -> new BusinessRuleException("dispute.assignee_invalid",
                "Analista inexistente"));
        Guard.require(isEligible(user, creditorId), "dispute.assignee_invalid",
                "O analista precisa estar ativo, trabalhar contestações e ter acesso ao credor");
    }

    List<UserSummary> eligibleUsers(UUID creditorId) {
        Map<UUID, UserSummary> byId = new java.util.LinkedHashMap<>();
        for (String permission : List.of(Permission.DISPUTE_WORK.name(), Permission.DISPUTE_MANAGE.name())) {
            users.activeUsersWithPermission(permission).forEach(u -> byId.putIfAbsent(u.id(), u));
        }
        return byId.values().stream().filter(u -> isEligible(u, creditorId))
                .sorted(Comparator.comparing(UserSummary::name)).toList();
    }

    /**
     * Regra única de quem pode ser responsável por contestação do credor: ativo, {@code DISPUTE_WORK} ou
     * {@code DISPUTE_MANAGE} e acesso ao credor. Usada na gravação e nas listas ({@code assigneeOptions},
     * {@code CaseAssigneeService}).
     */
    static boolean isEligible(UserSummary user, UUID creditorId) {
        return worksDisputes(user) && user.canAccessCreditor(creditorId);
    }

    /** Membro de grupo de distribuição: {@link #isEligible} no credor do grupo; grupo geral (nulo), só trabalhar contestações. */
    static boolean isEligibleForPool(UserSummary user, UUID creditorId) {
        return creditorId == null ? worksDisputes(user) : isEligible(user, creditorId);
    }

    /** Destino de redistribuição: {@link #isEligible} em todos os credores informados (vazio = só trabalhar contestações). */
    static boolean isEligibleForAll(UserSummary user, Set<UUID> creditorIds) {
        return worksDisputes(user) && creditorIds.stream().allMatch(user::canAccessCreditor);
    }

    private static boolean worksDisputes(UserSummary user) {
        return user != null && user.active() && (user.permissions().contains(Permission.DISPUTE_WORK.name())
                || user.permissions().contains(Permission.DISPUTE_MANAGE.name()));
    }

    Actor actor() {
        return currentActor.get();
    }
}
