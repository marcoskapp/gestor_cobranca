package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.Assignment;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.DistributionPreview;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.DistributionResult;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.MemberPreview;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.TierBandCount;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.TierCount;
import br.com.gestorcobranca.collection.application.port.out.CaseSelector;
import br.com.gestorcobranca.collection.domain.model.AssignmentMode;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.CollectionQueue;
import br.com.gestorcobranca.collection.domain.model.DistributionStrategy;
import br.com.gestorcobranca.collection.domain.model.PerformanceTier;
import br.com.gestorcobranca.collection.domain.model.PotentialBand;
import br.com.gestorcobranca.collection.domain.model.TierPolicy;
import br.com.gestorcobranca.collection.domain.repository.CollectionCaseRepository;
import br.com.gestorcobranca.collection.domain.repository.CollectionQueueRepository;
import br.com.gestorcobranca.collection.domain.repository.NegotiatorTierRepository;
import br.com.gestorcobranca.collection.domain.service.CaseDistributor;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.Permission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Distribuição em lote de uma fila (o "Distribuir" manual, a rodada automática e a prévia usam o mesmo plano):
 * seleciona os casos disponíveis, monta os membros (operadores ativos com {@code CASE_WORK} das equipes da fila, com a
 * carga atual e, na estratégia por nível, o nível projetado do {@code incentive}), planeja com
 * {@link CaseDistributor} e aplica as atribuições pelo {@link CaseRecorder}, gravando no histórico o nível e a faixa de
 * potencial. Chamado dentro da transação do caso de uso; o escopo de quem dispara é conferido antes.
 */
@Component
class QueueDistribution {

    private static final Logger LOG = LoggerFactory.getLogger(QueueDistribution.class);

    /** Origem da rodada (auditoria, evento de negócio e métricas). */
    enum Trigger { MANUAL, AUTOMATIC }

    private final CollectionCaseRepository cases;
    private final CollectionQueueRepository queues;
    private final CaseSelector selector;
    private final CaseRecorder recorder;
    private final UserDirectory users;
    private final NegotiatorTierRepository tiers;
    private final SettingsProvider settings;
    private final TierVisibility tierVisibility;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final CollectionMetrics metrics;
    private final Clock clock;

    QueueDistribution(CollectionCaseRepository cases, CollectionQueueRepository queues, CaseSelector selector,
                      CaseRecorder recorder, UserDirectory users, NegotiatorTierRepository tiers,
                      SettingsProvider settings, TierVisibility tierVisibility, AuditTrail auditTrail,
                      BusinessEventLogger businessEvents, CollectionMetrics metrics, Clock clock) {
        this.cases = cases;
        this.queues = queues;
        this.selector = selector;
        this.recorder = recorder;
        this.users = users;
        this.tiers = tiers;
        this.settings = settings;
        this.tierVisibility = tierVisibility;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Plano montado com os casos e as cargas de agora. */
    private record Planned(CaseDistributor.Plan plan, Map<UUID, UserSummary> members,
                           List<CaseDistributor.Member> plannerMembers, int considered, TierPolicy policy) {
    }

    /** Distribui e grava (casos bloqueados com {@code SKIP LOCKED}). */
    DistributionResult run(CollectionQueue queue, int limit, Trigger trigger) {
        long started = System.nanoTime();
        Planned planned = plan(queue, limit, true);
        CaseDistributor.Plan plan = planned.plan();
        List<Assignment> done = apply(plan, planned.members(), queue);
        queue.advanceRoundRobin(plan.cursor());
        queues.save(queue);
        Map<String, Integer> byBand = countByBand(plan);
        Map<String, Integer> byTier = countByTier(plan);
        AuditEntry audit = AuditEntry.of("collection_queue.distributed", "collection_queue", queue.getId())
                .creditor(queue.getCreditorId())
                .meta("strategy", queue.getStrategy())
                .meta("trigger", trigger)
                .meta("assigned", done.size())
                .meta("unassigned", plan.unassigned().size())
                .meta("members", planned.members().size());
        BusinessEvent event = BusinessEvent.of(BusinessEventTypes.QUEUE_DISTRIBUTED)
                .creditor(queue.getCreditorId())
                .reference("collection_queue", queue.getId())
                .durationMs(Duration.ofNanos(System.nanoTime() - started).toMillis())
                .attr("strategy", queue.getStrategy())
                .attr("trigger", trigger)
                .attr("considered", planned.considered())
                .attr("assigned", done.size())
                .attr("unassigned", plan.unassigned().size())
                .attr("members", planned.members().size());
        if (queue.getStrategy() == DistributionStrategy.TIERED) {
            // só agregados por faixa e por nível — nunca o nível de uma pessoa
            audit.meta("byBand", byBand).meta("byTier", byTier);
            byBand.forEach((band, n) -> event.attr("band" + band, n));
            byTier.forEach((tier, n) -> event.attr("tier" + tier, n));
            plan.assignments().forEach(a -> metrics.tieredAssignment(a.tier().name(), a.band().name()));
        }
        auditTrail.record(audit);
        businessEvents.log(event);
        metrics.distribution(queue.getStrategy().name(), done.size(), plan.unassigned().size());
        metrics.distributionRun(trigger.name(), queue.getStrategy().name());
        LOG.atInfo().addKeyValue("queueId", queue.getId()).addKeyValue("strategy", queue.getStrategy().name())
                .addKeyValue("trigger", trigger.name()).addKeyValue("assigned", done.size())
                .addKeyValue("unassigned", plan.unassigned().size())
                .log("Distribuição de casos da fila concluída");
        return new DistributionResult(done.size(), plan.unassigned().size(), done);
    }

    /**
     * Prévia: o mesmo plano, sem bloquear nem gravar. Quantidades por nível ({@code byTier} e o nível em
     * {@code byTierBand}) só se o ator vê o nível de <b>todos</b> os membros da fila: com poucas pessoas, a contagem por
     * nível somada ao "planejado" de cada um revelaria o nível de quem ele não pode ver. Sem essa visão, só totais por
     * faixa (nível nulo) e nenhum {@code byTier}.
     */
    /**
     * Resultado devolvido a quem disparou o "Distribuir": em fila por nível, a lista por caso só traz as pessoas cujo
     * nível o ator pode ver (a contagem por pessoa revelaria o nível); os totais continuam completos.
     */
    DistributionResult forActor(CollectionQueue queue, DistributionResult result) {
        if (queue.getStrategy() != DistributionStrategy.TIERED) {
            return result;
        }
        Predicate<UUID> visible = tierVisibility.visibleUsers();
        return new DistributionResult(result.assigned(), result.unassigned(),
                result.assignments().stream().filter(a -> visible.test(a.userId())).toList());
    }

    DistributionPreview preview(CollectionQueue queue, int limit) {
        Planned planned = plan(queue, limit, false);
        CaseDistributor.Plan plan = planned.plan();
        boolean tiered = queue.getStrategy() == DistributionStrategy.TIERED;
        Map<UUID, Integer> plannedByUser = new HashMap<>();
        plan.assignments().forEach(a -> plannedByUser.merge(a.userId(), 1, Integer::sum));
        Predicate<UUID> visible = tiered ? tierVisibility.visibleUsers() : user -> false;
        boolean allVisible = tiered && planned.plannerMembers().stream().allMatch(m -> visible.test(m.userId()));
        List<MemberPreview> members = new ArrayList<>();
        Map<PerformanceTier, int[]> tierTotals = new EnumMap<>(PerformanceTier.class);
        for (CaseDistributor.Member m : planned.plannerMembers()) {
            int count = plannedByUser.getOrDefault(m.userId(), 0);
            if (tiered) {
                PerformanceTier tier = planned.policy().effective(m.tier());
                int[] totals = tierTotals.computeIfAbsent(tier, t -> new int[2]);
                totals[0]++;
                totals[1] += count;
                // em fila por nível, a carga e os casos planejados de uma pessoa revelam o nível dela (capacidade por
                // nível e faixas): só aparecem as pessoas cujo nível o ator pode ver; as demais ficam nos totais por faixa
                if (visible.test(m.userId())) {
                    members.add(new MemberPreview(m.userId(), tier.name(), m.load(),
                            planned.policy().effectiveCapacity(m.capacity(), tier), count));
                }
            } else {
                members.add(new MemberPreview(m.userId(), null, m.load(), m.capacity(), count));
            }
        }
        List<TierBandCount> byTierBand = new ArrayList<>();
        List<TierCount> byTier = new ArrayList<>();
        if (allVisible) {
            Map<String, Integer> pairs = new LinkedHashMap<>();
            for (PerformanceTier tier : PerformanceTier.values()) {
                for (PotentialBand band : PotentialBand.values()) {
                    pairs.put(tier.name() + "|" + band.name(), 0);
                }
            }
            plan.assignments().forEach(a -> pairs.merge(a.tier().name() + "|" + a.band().name(), 1, Integer::sum));
            pairs.forEach((key, n) -> {
                if (n > 0) {
                    String[] parts = key.split("\\|");
                    byTierBand.add(new TierBandCount(parts[0], parts[1], n));
                }
            });
            tierTotals.forEach((tier, totals) -> byTier.add(new TierCount(tier.name(), totals[0], totals[1])));
        } else if (tiered) {
            Map<PotentialBand, Integer> bands = new EnumMap<>(PotentialBand.class);
            plan.assignments().forEach(a -> bands.merge(a.band(), 1, Integer::sum));
            bands.forEach((band, n) -> byTierBand.add(new TierBandCount(null, band.name(), n)));
        }
        members.sort(Comparator.comparingInt(MemberPreview::planned).reversed().thenComparing(MemberPreview::userId));
        return new DistributionPreview(queue.getId(), queue.getStrategy().name(), planned.considered(),
                plan.assignments().size(), plan.unassigned().size(), byTierBand, byTier, members);
    }

    private Planned plan(CollectionQueue queue, int limit, boolean lock) {
        Map<UUID, UserSummary> members = queueMembers(queue);
        Map<UUID, Long> loads = cases.countActiveAssignedByUser(queue.getId());
        boolean tiered = queue.getStrategy() == DistributionStrategy.TIERED;
        Map<UUID, PerformanceTier> tierOf = tiered
                ? tiers.currentTiers(members.keySet(), CollectionViews.today(clock)) : Map.of();
        TierPolicy policy = tiered ? settings.tierPolicy() : TierPolicy.DEFAULT;
        List<CaseDistributor.Member> plannerMembers = members.values().stream()
                .map(u -> new CaseDistributor.Member(u.id(), loads.getOrDefault(u.id(), 0L).intValue(),
                        queue.getMaxCasesPerUser(), u.allCreditors(), u.creditorIds(), tierOf.get(u.id())))
                .toList();
        Instant now = Instant.now(clock);
        List<CaseDistributor.Candidate> candidates;
        if (tiered) {
            List<CaseSelector.RankedCandidate> ranked = lock ? selector.lockAvailableByPotential(queue.getId(), now, limit)
                    : selector.listAvailableByPotential(queue.getId(), now, limit);
            candidates = ranked.stream().map(r -> new CaseDistributor.Candidate(r.caseId(), r.creditorId(), r.score(),
                    r.eligibleBalance(), r.band())).toList();
        } else {
            List<CaseSelector.Candidate> plain = lock ? selector.lockAvailable(queue.getId(), queue.getOrdering(), now, limit)
                    : selector.listAvailable(queue.getId(), queue.getOrdering(), now, limit);
            candidates = plain.stream().map(k -> new CaseDistributor.Candidate(k.caseId(), k.creditorId())).toList();
        }
        CaseDistributor.Plan plan = CaseDistributor.plan(candidates, plannerMembers, queue.getStrategy(),
                queue.getRoundRobinCursor(), policy);
        return new Planned(plan, members, plannerMembers, candidates.size(), policy);
    }

    private List<Assignment> apply(CaseDistributor.Plan plan, Map<UUID, UserSummary> members, CollectionQueue queue) {
        Instant now = Instant.now(clock);
        List<Assignment> done = new ArrayList<>();
        for (CaseDistributor.Assignment a : plan.assignments()) {
            CollectionCase c = cases.findById(a.caseId())
                    .orElseThrow(() -> ResourceNotFoundException.of("collection_case", a.caseId()));
            CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
            c.assignToUser(a.userId(), CaseWorkService.teamFor(queue, members.get(a.userId()).teamIds()), now);
            CaseRecorder.TierMotive motive = a.tier() == null ? null : new CaseRecorder.TierMotive(a.tier(), a.band());
            recorder.commit(c, before, AssignmentMode.DISTRIBUTION, null, "distribution", motive);
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

    private static Map<String, Integer> countByBand(CaseDistributor.Plan plan) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        plan.assignments().stream().filter(a -> a.band() != null)
                .forEach(a -> counts.merge(a.band().name(), 1, Integer::sum));
        return counts;
    }

    private static Map<String, Integer> countByTier(CaseDistributor.Plan plan) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        plan.assignments().stream().filter(a -> a.tier() != null)
                .forEach(a -> counts.merge(a.tier().name(), 1, Integer::sum));
        return counts;
    }
}
