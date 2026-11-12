package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.incentive.application.port.in.TierScheduling;
import br.com.gestorcobranca.incentive.application.port.in.TierUseCases;
import br.com.gestorcobranca.incentive.application.port.out.TierSampleReader;
import br.com.gestorcobranca.incentive.domain.model.tier.PersonSample;
import br.com.gestorcobranca.incentive.domain.model.tier.Tier;
import br.com.gestorcobranca.incentive.domain.model.tier.TierAssignment;
import br.com.gestorcobranca.incentive.domain.model.tier.TierMatrix;
import br.com.gestorcobranca.incentive.domain.model.tier.TierPeriodicity;
import br.com.gestorcobranca.incentive.domain.model.tier.TierProposal;
import br.com.gestorcobranca.incentive.domain.model.tier.TierRound;
import br.com.gestorcobranca.incentive.domain.model.tier.TierRoundStatus;
import br.com.gestorcobranca.incentive.domain.model.tier.TierSettings;
import br.com.gestorcobranca.incentive.domain.repository.TierRepository;
import br.com.gestorcobranca.incentive.domain.service.TierClassifier;
import br.com.gestorcobranca.incentive.domain.service.TierSchedule;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.incentive.NegotiatorTierChanged;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Classificação de desempenho do negociador (distribuicao-por-desempenho.md §3, §11.2): apuração pela
 * {@link TierClassifier}, revisão humana com ajuste e motivo, publicação (eventos {@link NegotiatorTierChanged}) ou
 * expiração. Notas, quartis e valores nunca vão a logs técnicos, eventos ou métricas: só ids, contagens e o nível
 * publicado.
 */
@Service
@Transactional
class TierService implements TierUseCases, TierScheduling {

    private static final String RESOURCE = "tier_round";
    private static final int ROUND_LIST_LIMIT = 24;

    private final TierRepository tiers;
    private final TierSampleReader samples;
    private final UserDirectory users;
    private final IncentiveAccess access;
    private final HolidayCalendar calendar;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final ApplicationEventPublisher events;
    private final IncentiveMetrics metrics;
    private final Clock clock;

    TierService(TierRepository tiers, TierSampleReader samples, UserDirectory users, IncentiveAccess access,
                HolidayCalendar calendar, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                ApplicationEventPublisher events, IncentiveMetrics metrics, Clock clock) {
        this.tiers = tiers;
        this.samples = samples;
        this.users = users;
        this.access = access;
        this.calendar = calendar;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ parâmetros

    @Override
    @Transactional(readOnly = true)
    public SettingsView settings() {
        access.requirePermission(Permission.TIER_MANAGE);
        Optional<TierSettings> stored = tiers.settings();
        return settingsView(stored.orElseGet(TierSettings::defaults), stored.isPresent());
    }

    @Override
    public SettingsView updateSettings(SettingsCommand c) {
        access.requirePermission(Permission.TIER_MANAGE);
        TierSettings next = new TierSettings(c.windowDays(), c.minEvaluations(), c.minCases(), c.qualityGate(),
                TierMatrix.of(c.matrix()), Tier.parse(c.defaultTier(), "tier_settings.default_tier_invalid"),
                periodicity(c.periodicity()), c.reviewBusinessDays());
        TierSettings before = current();
        tiers.saveSettings(next, access.actor().id(), Instant.now(clock));
        auditTrail.record(AuditEntry.of("tier_settings.updated", "tier_settings", access.actor().tenantId())
                .change("windowDays", before.windowDays(), next.windowDays())
                .change("minEvaluations", before.minEvaluations(), next.minEvaluations())
                .change("minCases", before.minCases(), next.minCases())
                .change("qualityGate", before.qualityGate(), next.qualityGate())
                .change("matrix", before.matrix().toString(), next.matrix().toString())
                .change("defaultTier", before.defaultTier(), next.defaultTier())
                .change("periodicity", before.periodicity(), next.periodicity())
                .change("reviewBusinessDays", before.reviewBusinessDays(), next.reviewBusinessDays()));
        return settingsView(next, true);
    }

    // ------------------------------------------------------------------ rodadas

    @Override
    @Transactional(readOnly = true)
    public List<RoundSummary> rounds() {
        requireReviewer();
        Set<UUID> visible = access.visibleUsers();
        List<TierRound> rounds = tiers.rounds(ROUND_LIST_LIMIT);
        // uma consulta só para as pessoas de todas as rodadas listadas (sem N+1)
        Map<UUID, List<TierAssignment>> people = tiers.assignments(rounds.stream().map(TierRound::id).toList());
        return rounds.stream()
                .map(r -> summary(r, inScope(people.getOrDefault(r.id(), List.of()), visible))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RoundDetail round(UUID roundId) {
        requireReviewer();
        TierRound round = tiers.findRound(roundId).orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, roundId));
        Set<UUID> visible = access.visibleUsers();
        List<TierAssignment> people = inScope(tiers.assignments(roundId), visible);
        access.logCompensationListing(RESOURCE, visible == null ? "ALL" : "TEAM");
        Set<UUID> names = new HashSet<>();
        people.forEach(a -> {
            names.add(a.userId());
            if (a.adjustedBy() != null) {
                names.add(a.adjustedBy());
            }
        });
        Map<UUID, String> nameOf = users.findAll(names).stream()
                .collect(Collectors.toMap(UserSummary::id, UserSummary::name, (a, b) -> a));
        UUID self = access.actor().id();
        List<PersonView> views = people.stream().map(a -> personView(a, nameOf, self))
                .sorted(Comparator.comparing(PersonView::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        boolean open = round.status() == TierRoundStatus.PROPOSED && !round.isOverdue(today());
        boolean canAdjust = open && access.actor().has(Permission.TIER_REVIEW);
        return new RoundDetail(summary(round, people), settingsView(round.settings(), true), views, canAdjust,
                canAdjust && access.actor().has(Permission.PERFORMANCE_VIEW_ALL));
    }

    @Override
    public RoundSummary assessNow() {
        access.requirePermission(Permission.TIER_MANAGE);
        Guard.requireState(tiers.openRound().isEmpty(), "tier_round.open_exists",
                "Já há uma rodada em revisão: publique-a ou aguarde o prazo antes de apurar de novo");
        TierRound round = propose(TierRound.Trigger.MANUAL, access.actor().id());
        return summary(round, tiers.assignments(round.id()));
    }

    @Override
    public PersonView adjust(UUID roundId, UUID userId, AdjustCommand command) {
        access.requirePermission(Permission.TIER_REVIEW);
        access.requireVisible(userId, RESOURCE);
        UUID self = access.actor().id();
        Guard.require(!self.equals(userId), "tier_assignment.self_adjustment",
                "Ninguém ajusta o próprio nível; peça a outra pessoa da supervisão ou da gestão");
        // bloqueio compartilhado: a publicação/expiração (update da rodada) espera este ajuste terminar, e um ajuste
        // que chega depois lê a rodada já publicada; o gatilho da V1892 recusa no banco o que escapar
        TierRound round = tiers.findRoundForShare(roundId)
                .orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, roundId));
        round.requireOpen(today());
        // linha bloqueada: dois ajustes simultâneos da mesma pessoa não se sobrescrevem (e o "antes" da auditoria vale)
        TierAssignment assignment = tiers.assignmentForUpdate(roundId, userId)
                .orElseThrow(() -> ResourceNotFoundException.of("tier_assignment", userId));
        Tier before = assignment.finalTier();
        assignment.adjust(Tier.parse(command.tier(), "tier_assignment.tier_invalid"), command.reason(), self,
                Instant.now(clock));
        tiers.updateAssignment(assignment);
        auditTrail.record(AuditEntry.of("tier_assignment.adjusted", RESOURCE, roundId)
                .meta("userId", userId).change("finalTier", before, assignment.finalTier())
                .meta("proposedTier", assignment.proposal().proposedTier()).reason(assignment.adjustmentReason()));
        Map<UUID, String> nameOf = users.findAll(Set.of(userId, self)).stream()
                .collect(Collectors.toMap(UserSummary::id, UserSummary::name, (a, b) -> a));
        return personView(assignment, nameOf, self);
    }

    @Override
    public RoundSummary publish(UUID roundId) {
        access.requirePermission(Permission.TIER_REVIEW);
        access.requirePermission(Permission.PERFORMANCE_VIEW_ALL);
        TierRound round = tiers.findRound(roundId).orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, roundId));
        Optional<TierRound> previous = tiers.lastPublished();
        Instant now = Instant.now(clock);
        LocalDate today = today();
        round.publish(access.actor().id(), now, today);
        Guard.requireState(tiers.updateStatusFromProposed(round), "tier_round.not_proposed",
                "Esta rodada não está mais em revisão");
        List<TierAssignment> assignments = tiers.assignments(roundId);
        Set<UUID> active = users.activeUsersWithPermission(Permission.CASE_WORK.name()).stream()
                .map(UserSummary::id).collect(Collectors.toSet());
        UUID tenantId = access.actor().tenantId();
        Set<UUID> emitted = new HashSet<>();
        Map<Tier, Long> counts = new EnumMap<>(Tier.class);
        for (TierAssignment a : assignments) {
            if (active.contains(a.userId())) {
                events.publishEvent(new NegotiatorTierChanged(Ids.newId(), tenantId, now, a.userId(),
                        a.finalTier().name(), today, roundId));
                emitted.add(a.userId());
                counts.merge(a.finalTier(), 1L, Long::sum);
            }
        }
        // quem tinha nível na publicação anterior e perdeu CASE_WORK ou foi desativado volta ao padrão
        int cleared = 0;
        if (previous.isPresent()) {
            for (TierAssignment a : tiers.assignments(previous.get().id())) {
                if (!emitted.contains(a.userId())) {
                    events.publishEvent(new NegotiatorTierChanged(Ids.newId(), tenantId, now, a.userId(), null, today,
                            roundId));
                    emitted.add(a.userId());
                    cleared++;
                }
            }
        }
        long adjusted = assignments.stream().filter(TierAssignment::adjusted).count();
        auditTrail.record(AuditEntry.of("tier_round.published", RESOURCE, roundId)
                .change("status", TierRoundStatus.PROPOSED, TierRoundStatus.PUBLISHED)
                .meta("validFrom", today).meta("published", emitted.size() - cleared).meta("cleared", cleared)
                .meta("adjusted", adjusted));
        BusinessEvent event = BusinessEvent.of(BusinessEventTypes.TIER_ROUND_PUBLISHED).reference(RESOURCE, roundId)
                .attr("people", emitted.size() - cleared).attr("cleared", cleared).attr("adjusted", adjusted);
        for (Tier tier : Tier.values()) {
            event.attr("tier" + tier.name(), counts.getOrDefault(tier, 0L));
        }
        businessEvents.log(event);
        metrics.tierRound("published");
        return summary(round, inScope(assignments, access.visibleUsers()));
    }

    @Override
    @Transactional(readOnly = true)
    public MyTierView mine() {
        access.requirePermission(Permission.PERFORMANCE_VIEW_OWN);
        UUID self = access.actor().id();
        Optional<TierRound> published = tiers.lastPublished();
        TierSettings settings = published.map(TierRound::settings).orElseGet(this::current);
        if (published.isEmpty()) {
            return new MyTierView("NONE", settings.defaultTier().name(), settings.defaultTier().name(), null, null,
                    null, null, null, false, false, false, 0, 0, settings.minEvaluations(), settings.minCases(),
                    settings.qualityGate(), false, settings.matrix().toMap());
        }
        TierRound round = published.get();
        Optional<TierAssignment> mine = tiers.assignment(round.id(), self);
        if (mine.isEmpty()) {
            return new MyTierView("NOT_CLASSIFIED", settings.defaultTier().name(), settings.defaultTier().name(),
                    round.validFrom(), round.periodFrom(), round.periodTo(), null, null, false, false, false, 0, 0,
                    settings.minEvaluations(), settings.minCases(), settings.qualityGate(), false,
                    settings.matrix().toMap());
        }
        TierAssignment a = mine.get();
        TierProposal p = a.proposal();
        return new MyTierView("CLASSIFIED", a.finalTier().name(), settings.defaultTier().name(), round.validFrom(),
                round.periodFrom(), round.periodTo(), p.qualityQuartile(), p.efficiencyQuartile(),
                p.qualityGateFailed(), p.missingQuality(), p.missingEfficiency(), p.evaluations(), p.cases(),
                settings.minEvaluations(), settings.minCases(), settings.qualityGate(), a.adjusted(),
                settings.matrix().toMap());
    }

    // ------------------------------------------------------------------ rotina

    /**
     * Rotina diária (ator de sistema), primeira etapa: expira a rodada em revisão com o prazo encerrado (nada muda:
     * vale a publicação anterior). Transação própria: uma falha ao abrir a rodada nova não desfaz a expiração.
     */
    @Override
    public void expireOverdueRound() {
        LocalDate today = today();
        Instant now = Instant.now(clock);
        tiers.openRound().filter(r -> r.isOverdue(today)).ifPresent(r -> {
            r.expire(now, today);
            if (tiers.updateStatusFromProposed(r)) {
                auditTrail.record(AuditEntry.of("tier_round.expired", RESOURCE, r.id())
                        .change("status", TierRoundStatus.PROPOSED, TierRoundStatus.EXPIRED)
                        .meta("reviewDeadline", r.reviewDeadline()));
                businessEvents.log(BusinessEvent.of(BusinessEventTypes.TIER_ROUND_EXPIRED).reference(RESOURCE, r.id()));
                metrics.tierRound("expired");
            }
        });
    }

    /**
     * Rotina diária, segunda etapa: abre a rodada automática <b>devida no período</b> (mensal: a partir do 1º dia útil
     * do mês; semanal: a partir da segunda-feira) se ainda não houver rodada automática criada desde o início do
     * período (no fuso de operação) nem outra rodada em revisão. Uma execução perdida no dia de início é recuperada na
     * próxima execução do mesmo período.
     */
    @Override
    public void openScheduledRound() {
        LocalDate today = today();
        TierSettings settings = current();
        LocalDate firstBusinessDay = calendar.firstBusinessDayOfMonth(today);
        if (!TierSchedule.isDue(settings.periodicity(), today, firstBusinessDay) || tiers.openRound().isPresent()) {
            return;
        }
        Instant periodStart = TierSchedule.periodStart(settings.periodicity(), today, firstBusinessDay)
                .atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        if (tiers.scheduledRoundSince(periodStart)) {
            return;
        }
        propose(TierRound.Trigger.SCHEDULED, null);
    }

    // ------------------------------------------------------------------ apuração

    private TierRound propose(TierRound.Trigger trigger, UUID by) {
        TierSettings settings = current();
        LocalDate today = today();
        Instant now = Instant.now(clock);
        TierRound round = TierRound.propose(today, trigger, settings,
                calendar.plusBusinessDays(today, settings.reviewBusinessDays()), now, by);
        List<UUID> negotiators = users.activeUsersWithPermission(Permission.CASE_WORK.name()).stream()
                .map(UserSummary::id).distinct().toList();
        Map<UUID, PersonSample> found = samples.samples(negotiators, round.periodFrom(), round.periodTo());
        List<PersonSample> all = negotiators.stream().map(id -> found.getOrDefault(id, PersonSample.empty(id))).toList();
        List<TierAssignment> assignments = TierClassifier.classify(all, settings).stream()
                .map(p -> TierAssignment.proposed(round.id(), p)).toList();
        tiers.insertRound(round, assignments);
        long missing = assignments.stream().filter(a -> a.proposal().missingSample()).count();
        auditTrail.record(AuditEntry.of("tier_round.proposed", RESOURCE, round.id())
                .created("periodFrom", round.periodFrom()).created("periodTo", round.periodTo())
                .created("origin", trigger).created("reviewDeadline", round.reviewDeadline())
                .created("people", assignments.size()).created("missingSample", missing));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.TIER_ROUND_PROPOSED).reference(RESOURCE, round.id())
                .attr("origin", trigger).attr("people", assignments.size()).attr("missingSample", missing)
                .attr("windowDays", settings.windowDays()));
        metrics.tierRound("proposed_" + trigger.name().toLowerCase());
        return round;
    }

    // ------------------------------------------------------------------ auxiliares

    private TierSettings current() {
        return tiers.settings().orElseGet(TierSettings::defaults);
    }

    private void requireReviewer() {
        Actor actor = access.actor();
        if (!actor.has(Permission.TIER_REVIEW) && !actor.has(Permission.TIER_MANAGE)) {
            access.requirePermission(Permission.TIER_REVIEW);
        }
        // pessoas só no escopo de desempenho de quem vê (equipe ou todos)
        if (access.scope() == IncentiveAccess.Scope.OWN) {
            throw new ScopeAccessDeniedException();
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }

    private static TierPeriodicity periodicity(String value) {
        try {
            return TierPeriodicity.valueOf(value == null ? "" : value.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("tier_settings.periodicity_invalid", "Periodicidade: semanal ou mensal");
        }
    }

    private static List<TierAssignment> inScope(Collection<TierAssignment> assignments, Set<UUID> visible) {
        return assignments.stream().filter(a -> visible == null || visible.contains(a.userId())).toList();
    }

    private static RoundSummary summary(TierRound r, Collection<TierAssignment> people) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Tier tier : Tier.values()) {
            counts.put(tier.name(), people.stream().filter(a -> a.finalTier() == tier).count());
        }
        int adjusted = (int) people.stream().filter(TierAssignment::adjusted).count();
        return new RoundSummary(r.id(), r.periodFrom(), r.periodTo(), r.status().name(), r.trigger().name(),
                r.reviewDeadline(), r.createdAt(), r.publishedAt(), r.validFrom(), r.expiredAt(), people.size(), counts,
                adjusted);
    }

    private static SettingsView settingsView(TierSettings s, boolean customized) {
        return new SettingsView(s.windowDays(), s.minEvaluations(), s.minCases(), s.qualityGate(), s.matrix().toMap(),
                s.defaultTier().name(), s.periodicity().name(), s.reviewBusinessDays(), customized);
    }

    private static PersonView personView(TierAssignment a, Map<UUID, String> nameOf, UUID self) {
        TierProposal p = a.proposal();
        return new PersonView(a.userId(), nameOf.get(a.userId()), p.qualityQuartile(), p.efficiencyQuartile(),
                p.qualityGateFailed(), p.evaluations(), p.qualityAverage(), p.cases(), p.recoveryRatio(),
                p.proposedTier().name(), a.finalTier().name(), a.adjusted(),
                a.adjustedBy() == null ? null : nameOf.get(a.adjustedBy()), a.adjustedAt(), a.adjustmentReason(),
                a.userId().equals(self));
    }
}
