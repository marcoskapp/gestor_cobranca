package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases;
import br.com.gestorcobranca.collection.application.port.out.CaseSelector;
import br.com.gestorcobranca.collection.domain.model.AssignmentMode;
import br.com.gestorcobranca.collection.domain.model.CaseStatus;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.CollectionQueue;
import br.com.gestorcobranca.collection.domain.model.DistributionStrategy;
import br.com.gestorcobranca.collection.domain.repository.CaseAssignmentRepository;
import br.com.gestorcobranca.collection.domain.repository.CaseSearchCriteria;
import br.com.gestorcobranca.collection.domain.repository.CollectionCaseRepository;
import br.com.gestorcobranca.collection.domain.repository.CollectionQueueRepository;
import br.com.gestorcobranca.collection.domain.service.CaseDistributor;
import br.com.gestorcobranca.identity.application.port.in.TeamUseCases;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Service
@Transactional
class CaseWorkService implements CaseWorkUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(CaseWorkService.class);
    private static final int REASON_MAX = 500;
    private static final int MAX_DISTRIBUTION = 1000;
    private static final int MAX_REDISTRIBUTION = 2000;

    private final CollectionCaseRepository cases;
    private final CollectionQueueRepository queues;
    private final CaseAssignmentRepository assignments;
    private final CaseSelector selector;
    private final CaseAccess access;
    private final CaseRecorder recorder;
    private final UserDirectory users;
    private final TeamUseCases teams;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;
    private final CollectionMetrics metrics;
    private final Clock clock;

    CaseWorkService(CollectionCaseRepository cases, CollectionQueueRepository queues,
                    CaseAssignmentRepository assignments, CaseSelector selector, CaseAccess access,
                    CaseRecorder recorder, UserDirectory users, TeamUseCases teams, AccessScope accessScope,
                    AuditTrail auditTrail, CollectionMetrics metrics, Clock clock) {
        this.cases = cases;
        this.queues = queues;
        this.assignments = assignments;
        this.selector = selector;
        this.access = access;
        this.recorder = recorder;
        this.users = users;
        this.teams = teams;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
        this.metrics = metrics;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ próximo caso

    @Override
    public Optional<CaseView> nextCase(UUID queueId) {
        long started = System.nanoTime();
        Actor actor = requireUser();
        Instant now = Instant.now(clock);
        LocalDate today = CollectionViews.today(clock);
        Set<UUID> allowed = access.creditorFilter();

        Optional<UUID> own = selector.lockOwnDue(actor.id(), allowed, now);
        if (own.isPresent()) {
            CollectionCase c = load(own.get());
            metrics.nextCase("own_callback", elapsed(started));
            return Optional.of(CollectionViews.caseView(c, today));
        }

        Set<UUID> userTeams = users.teamsOf(actor.id());
        boolean capacityReached = false;
        for (CollectionQueue queue : candidateQueues(queueId, userTeams)) {
            Integer capacity = queue.getMaxCasesPerUser();
            if (capacity != null && cases.countActiveAssigned(queue.getId(), actor.id()) >= capacity) {
                capacityReached = true;
                continue;
            }
            Optional<UUID> next = selector.lockNextAvailable(queue.getId(), queue.getOrdering(),
                    restrictToQueue(allowed, queue), now);
            if (next.isEmpty()) {
                continue;
            }
            CollectionCase c = load(next.get());
            CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
            c.assignToUser(actor.id(), teamFor(queue, userTeams), now);
            CollectionCase saved = recorder.commit(c, before, AssignmentMode.NEXT_CASE, null, "next_case");
            metrics.nextCase("queue", elapsed(started));
            return Optional.of(CollectionViews.caseView(saved, today));
        }
        if (capacityReached && queueId != null) {
            metrics.nextCase("capacity", elapsed(started));
            throw new ConflictException("queue.capacity_reached",
                    "Limite de casos ativos por operador na fila alcançado: conclua ou devolva casos antes de pedir outro");
        }
        metrics.nextCase(capacityReached ? "capacity" : "empty", elapsed(started));
        return Optional.empty();
    }

    /** Fila informada (ativa e atendida pelo operador) ou as filas atendidas pelas equipes do operador. */
    private List<CollectionQueue> candidateQueues(UUID queueId, Set<UUID> userTeams) {
        if (queueId != null) {
            CollectionQueue queue = queues.findById(queueId)
                    .orElseThrow(() -> ResourceNotFoundException.of("collection_queue", queueId));
            if (queue.getCreditorId() != null) {
                accessScope.requireVisible("collection_queue", queueId, queue.getCreditorId());
            }
            Guard.requireState(queue.isActive(), "queue.inactive", "Fila desativada");
            if (!queue.isServedBy(userTeams)) {
                throw new ConflictException("queue.not_served_by_user", "A fila não é atendida pelas equipes do operador");
            }
            return List.of(queue);
        }
        return queues.findByActiveTrue().stream()
                .filter(q -> q.isServedBy(userTeams))
                .filter(q -> q.getCreditorId() == null || accessScope.canAccessCreditor(q.getCreditorId()))
                .sorted(Comparator.comparingInt(CollectionQueue::getPriority).thenComparing(CollectionQueue::getCode))
                .toList();
    }

    private static Set<UUID> restrictToQueue(Set<UUID> allowed, CollectionQueue queue) {
        if (queue.getCreditorId() == null) {
            return allowed;
        }
        if (allowed == null || allowed.contains(queue.getCreditorId())) {
            return Set.of(queue.getCreditorId());
        }
        return Set.of();
    }

    /** Equipe do operador que atende a fila; sem equipe na fila, a única equipe do operador (se houver só uma). */
    private static UUID teamFor(CollectionQueue queue, Set<UUID> userTeams) {
        Optional<UUID> serving = new TreeSet<>(userTeams).stream().filter(queue.getTeamIds()::contains).findFirst();
        if (serving.isPresent()) {
            return serving.get();
        }
        return userTeams.size() == 1 ? userTeams.iterator().next() : null;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CaseView> myCases(PageQuery page) {
        Actor actor = requireUser();
        Set<CaseStatus> active = EnumSet.complementOf(EnumSet.of(CaseStatus.CLOSED));
        CaseSearchCriteria criteria = new CaseSearchCriteria(access.creditorFilter(), null, null, null, active, null,
                actor.id(), null, null);
        LocalDate today = CollectionViews.today(clock);
        return PageResponse.of(cases.search(criteria, page.toPageable(Sort.by("nextActionAt").ascending()
                .and(Sort.by("id")))), c -> CollectionViews.caseView(c, today));
    }

    // ------------------------------------------------------------------ atribuição

    @Override
    public CaseView assign(UUID caseId, AssignCommand command) {
        Guard.require(command != null && (command.userId() != null || command.teamId() != null),
                "case.assignee_required", "Informe o operador ou a equipe");
        String reason = reason(command.reason());
        CollectionCase c = access.forCommand(caseId);
        requireActive(c);
        CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
        Instant now = Instant.now(clock);
        if (command.userId() != null) {
            UserSummary user = requireWorker(command.userId(), c.getCreditorId());
            UUID team = command.teamId() != null ? requireTeamOf(command.teamId(), user) : defaultTeam(c, user);
            c.assignToUser(user.id(), team, now);
        } else {
            teams.get(command.teamId());
            c.assignToTeam(command.teamId(), now);
        }
        return view(recorder.commit(c, before, AssignmentMode.MANUAL, reason, "manual"));
    }

    @Override
    public CaseView release(UUID caseId, String reason) {
        CollectionCase c = access.forCommand(caseId);
        requireActive(c);
        Actor actor = access.actor();
        if (!c.isAssignedTo(actor.id()) && !actor.has(Permission.CASE_ASSIGN)) {
            throw new ConflictException("case.not_assigned_to_user", "Somente o responsável devolve o caso à fila");
        }
        Guard.requireState(c.getAssigneeUserId() != null, "case.not_assigned", "O caso não está com um operador");
        CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
        c.release(Instant.now(clock));
        return view(recorder.commit(c, before, AssignmentMode.RELEASE, reason(reason), "release"));
    }

    @Override
    public CaseView takeOver(UUID caseId, String reason) {
        Actor actor = requireUser();
        CollectionCase c = access.forCommand(caseId);
        requireActive(c);
        if (c.isAssignedTo(actor.id())) {
            return view(c);
        }
        access.requireWorkable(c);
        CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
        Set<UUID> userTeams = users.teamsOf(actor.id());
        UUID team = c.getQueueId() == null ? (userTeams.size() == 1 ? userTeams.iterator().next() : null)
                : queues.findById(c.getQueueId()).map(q -> teamFor(q, userTeams)).orElse(null);
        c.assignToUser(actor.id(), team, Instant.now(clock));
        return view(recorder.commit(c, before, AssignmentMode.TAKE_OVER, reason(reason), "take_over"));
    }

    @Override
    public CaseView handToAssistant(UUID caseId, String reason) {
        CollectionCase c = access.forCommand(caseId);
        requireActive(c);
        Actor actor = access.actor();
        if (actor.isUser() && !c.isAssignedTo(actor.id()) && !actor.has(Permission.CASE_ASSIGN)) {
            throw new ConflictException("case.not_assigned_to_user", "Somente o responsável entrega o caso ao assistente");
        }
        CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
        c.handToAssistant(Instant.now(clock));
        return view(recorder.commit(c, before, AssignmentMode.ASSISTANT_HANDOFF, reason(reason), "assistant_handoff"));
    }

    // ------------------------------------------------------------------ distribuição

    @Override
    public DistributionResult distribute(UUID queueId, int limit) {
        Guard.require(limit >= 1 && limit <= MAX_DISTRIBUTION, "queue.distribution_limit_invalid",
                "Quantidade de casos entre 1 e %d".formatted(MAX_DISTRIBUTION));
        CollectionQueue queue = queues.findById(queueId)
                .orElseThrow(() -> ResourceNotFoundException.of("collection_queue", queueId));
        if (queue.getCreditorId() != null) {
            accessScope.requireVisible("collection_queue", queueId, queue.getCreditorId());
            accessScope.requireCreditor(queue.getCreditorId());
        } else {
            accessScope.requireAllCreditors("collection_queue");
        }
        Guard.requireState(queue.isActive(), "queue.inactive", "Fila desativada");
        if (queue.getStrategy() == DistributionStrategy.PULL) {
            throw new ConflictException("queue.pull_only", "A fila é atendida sob demanda (próximo caso), sem distribuição");
        }
        Map<UUID, UserSummary> members = queueMembers(queue);
        Map<UUID, Long> loads = cases.countActiveAssignedByUser(queue.getId());
        List<CaseDistributor.Member> plannerMembers = members.values().stream()
                .map(u -> new CaseDistributor.Member(u.id(), loads.getOrDefault(u.id(), 0L).intValue(),
                        queue.getMaxCasesPerUser(), u.allCreditors(), u.creditorIds()))
                .toList();
        Instant now = Instant.now(clock);
        List<CaseDistributor.Candidate> candidates = selector.lockAvailable(queue.getId(), queue.getOrdering(), now, limit)
                .stream().map(k -> new CaseDistributor.Candidate(k.caseId(), k.creditorId())).toList();
        CaseDistributor.Plan plan = CaseDistributor.plan(candidates, plannerMembers, queue.getStrategy(),
                queue.getRoundRobinCursor());
        List<Assignment> done = apply(plan, members, queue, AssignmentMode.DISTRIBUTION, null, "distribution");
        queue.advanceRoundRobin(plan.cursor());
        queues.save(queue);
        auditTrail.record(AuditEntry.of("collection_queue.distributed", "collection_queue", queue.getId())
                .creditor(queue.getCreditorId())
                .meta("strategy", queue.getStrategy())
                .meta("assigned", done.size())
                .meta("unassigned", plan.unassigned().size())
                .meta("members", members.size()));
        metrics.distribution(queue.getStrategy().name(), done.size(), plan.unassigned().size());
        LOG.atInfo().addKeyValue("queueId", queue.getId()).addKeyValue("strategy", queue.getStrategy().name())
                .addKeyValue("assigned", done.size()).addKeyValue("unassigned", plan.unassigned().size())
                .log("Distribuição de casos da fila concluída");
        return new DistributionResult(done.size(), plan.unassigned().size(), done);
    }

    @Override
    public DistributionResult redistribute(RedistributeCommand command) {
        Guard.require(command != null && command.fromUserId() != null, "case.redistribution_source_required",
                "Informe o operador de origem");
        String reason = reason(command.reason());
        Guard.require(!command.toUserIds().contains(command.fromUserId()), "case.redistribution_target_invalid",
                "O operador de origem não pode receber os próprios casos");
        List<CollectionCase> owned = cases.findActiveByAssignee(command.fromUserId(), MAX_REDISTRIBUTION).stream()
                .filter(c -> accessScope.canAccessCreditor(c.getCreditorId())).toList();
        Instant now = Instant.now(clock);
        if (command.toUserIds().isEmpty()) {
            List<Assignment> released = new ArrayList<>();
            for (CollectionCase c : owned) {
                CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
                c.release(now);
                recorder.commit(c, before, AssignmentMode.REDISTRIBUTION, reason, "redistribution");
                released.add(new Assignment(c.getId(), null));
            }
            metrics.distribution("RELEASE", 0, released.size());
            return new DistributionResult(0, released.size(), released);
        }
        Map<UUID, UserSummary> members = new LinkedHashMap<>();
        for (UUID userId : new TreeSet<>(command.toUserIds())) {
            UserSummary user = users.find(userId).filter(UserSummary::active)
                    .filter(u -> u.permissions().contains(Permission.CASE_WORK.name()))
                    .orElseThrow(() -> new BusinessRuleException("case.assignee_invalid",
                            "Operador de destino inexistente, inativo ou sem permissão para trabalhar casos"));
            members.put(user.id(), user);
        }
        List<CaseDistributor.Member> plannerMembers = members.values().stream()
                .map(u -> new CaseDistributor.Member(u.id(), (int) cases.countActiveByAssignee(u.id()), null,
                        u.allCreditors(), u.creditorIds()))
                .toList();
        List<CaseDistributor.Candidate> candidates = owned.stream()
                .map(c -> new CaseDistributor.Candidate(c.getId(), c.getCreditorId())).toList();
        CaseDistributor.Plan plan = CaseDistributor.plan(candidates, plannerMembers, DistributionStrategy.LEAST_LOADED, null);
        List<Assignment> done = apply(plan, members, null, AssignmentMode.REDISTRIBUTION, reason, "redistribution");
        // casos sem destino possível (escopo do destino) voltam à fila em vez de ficarem com quem saiu
        for (UUID caseId : plan.unassigned()) {
            CollectionCase c = load(caseId);
            CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
            c.release(now);
            recorder.commit(c, before, AssignmentMode.REDISTRIBUTION, reason, "redistribution");
        }
        metrics.distribution("REDISTRIBUTION", done.size(), plan.unassigned().size());
        return new DistributionResult(done.size(), plan.unassigned().size(), done);
    }

    private List<Assignment> apply(CaseDistributor.Plan plan, Map<UUID, UserSummary> members, CollectionQueue queue,
                                   AssignmentMode mode, String reason, String trigger) {
        Instant now = Instant.now(clock);
        List<Assignment> done = new ArrayList<>();
        Map<UUID, Set<UUID>> teamsByUser = new LinkedHashMap<>();
        for (CaseDistributor.Assignment a : plan.assignments()) {
            CollectionCase c = load(a.caseId());
            CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
            Set<UUID> userTeams = teamsByUser.computeIfAbsent(a.userId(), id -> members.get(id).teamIds());
            CollectionQueue caseQueue = queue != null ? queue
                    : c.getQueueId() == null ? null : queues.findById(c.getQueueId()).orElse(null);
            UUID team = caseQueue == null ? (userTeams.size() == 1 ? userTeams.iterator().next() : null)
                    : teamFor(caseQueue, userTeams);
            c.assignToUser(a.userId(), team, now);
            recorder.commit(c, before, mode, reason, trigger);
            done.add(new Assignment(c.getId(), a.userId()));
        }
        return done;
    }

    /** Operadores ativos com {@code CASE_WORK}: das equipes da fila, ou de toda a assessoria se a fila não tiver equipes. */
    private Map<UUID, UserSummary> queueMembers(CollectionQueue queue) {
        Map<UUID, UserSummary> members = new LinkedHashMap<>();
        List<UserSummary> candidates = queue.getTeamIds().isEmpty()
                ? users.activeUsersWithPermission(Permission.CASE_WORK.name())
                : new TreeSet<>(queue.getTeamIds()).stream().flatMap(t -> users.activeTeamMembers(t).stream()).toList();
        candidates.stream()
                .filter(u -> u.active() && u.permissions().contains(Permission.CASE_WORK.name()))
                .sorted(Comparator.comparing(UserSummary::id))
                .forEach(u -> members.putIfAbsent(u.id(), u));
        return members;
    }

    // ------------------------------------------------------------------ histórico

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AssignmentView> assignmentHistory(UUID caseId, PageQuery page) {
        CollectionCase c = access.visible(caseId);
        return PageResponse.of(assignments.findByCaseIdOrderByValidFromDesc(c.getId(), page.toPageable()),
                CollectionViews::assignment);
    }

    // ------------------------------------------------------------------ apoio

    private UserSummary requireWorker(UUID userId, UUID creditorId) {
        UserSummary user = users.find(userId).filter(UserSummary::active)
                .orElseThrow(() -> new BusinessRuleException("case.assignee_invalid", "Operador inexistente ou inativo"));
        Guard.require(user.permissions().contains(Permission.CASE_WORK.name()), "case.assignee_invalid",
                "O usuário não tem permissão para trabalhar casos");
        Guard.require(user.canAccessCreditor(creditorId), "case.assignee_out_of_scope",
                "O operador não tem acesso ao credor do caso");
        return user;
    }

    private UUID requireTeamOf(UUID teamId, UserSummary user) {
        Guard.require(user.teamIds().contains(teamId), "case.team_mismatch", "O operador não pertence à equipe informada");
        return teamId;
    }

    private UUID defaultTeam(CollectionCase c, UserSummary user) {
        if (c.getQueueId() != null) {
            Optional<CollectionQueue> queue = queues.findById(c.getQueueId());
            if (queue.isPresent()) {
                return teamFor(queue.get(), user.teamIds());
            }
        }
        return user.teamIds().size() == 1 ? user.teamIds().iterator().next() : null;
    }

    private Actor requireUser() {
        Actor actor = access.actor();
        if (!actor.isUser()) {
            throw new ScopeAccessDeniedException();
        }
        return actor;
    }

    private CollectionCase load(UUID caseId) {
        return cases.findById(caseId).orElseThrow(() -> ResourceNotFoundException.of("collection_case", caseId));
    }

    private static void requireActive(CollectionCase c) {
        Guard.requireState(c.getStatus().isActive(), "case.closed", "Caso encerrado");
    }

    private CaseView view(CollectionCase c) {
        return CollectionViews.caseView(c, CollectionViews.today(clock));
    }

    private static String reason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        Guard.require(reason.trim().length() <= REASON_MAX, "case.reason_too_long",
                "Motivo com mais de %d caracteres".formatted(REASON_MAX));
        return reason.trim();
    }

    private static Duration elapsed(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }
}
