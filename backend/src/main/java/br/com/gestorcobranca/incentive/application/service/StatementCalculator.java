package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries.TeamStructure;
import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries.WorkforceMember;
import br.com.gestorcobranca.incentive.application.port.out.FactStore;
import br.com.gestorcobranca.incentive.application.service.PlanResolver.ResolvedPlan;
import br.com.gestorcobranca.incentive.domain.model.Segment;
import br.com.gestorcobranca.incentive.domain.model.attribution.AgreementOrigin;
import br.com.gestorcobranca.incentive.domain.model.attribution.BeneficiaryType;
import br.com.gestorcobranca.incentive.domain.model.ledger.CommissionLedgerEntry;
import br.com.gestorcobranca.incentive.domain.model.plan.GoalMetric;
import br.com.gestorcobranca.incentive.domain.model.plan.GoalScope;
import br.com.gestorcobranca.incentive.domain.model.plan.GoalTarget;
import br.com.gestorcobranca.incentive.domain.model.plan.GoalUnit;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules;
import br.com.gestorcobranca.incentive.domain.model.plan.PoolDistribution;
import br.com.gestorcobranca.incentive.domain.model.quality.ConductOccurrence;
import br.com.gestorcobranca.incentive.domain.model.quality.OccurrenceType;
import br.com.gestorcobranca.incentive.domain.model.quality.QualityEvaluation;
import br.com.gestorcobranca.incentive.domain.model.statement.CommissionStatement;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementLine;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementOutcome;
import br.com.gestorcobranca.incentive.domain.repository.CommissionPlanRepository;
import br.com.gestorcobranca.incentive.domain.repository.QualityRepository;
import br.com.gestorcobranca.incentive.domain.service.CalculationResult;
import br.com.gestorcobranca.incentive.domain.service.CommissionEngine;
import br.com.gestorcobranca.incentive.domain.service.EngineInput;
import br.com.gestorcobranca.incentive.domain.service.EngineInput.GoalInput;
import br.com.gestorcobranca.incentive.domain.service.EngineInput.PoolShare;
import br.com.gestorcobranca.incentive.domain.service.EngineInput.QualityInput;
import br.com.gestorcobranca.incentive.domain.service.EngineInput.SegmentVolume;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Monta as entradas do motor para um negociador num período a partir do razão e dos fatos, roda o
 * motor e produz o cálculo do extrato com memória reprodutível (versão do plano, lançamentos e
 * entradas).
 */
@Component
class StatementCalculator {

    /** Versão do esquema da memória de cálculo gravada no extrato. */
    static final int MEMORY_SCHEMA = 1;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final PlanResolver planResolver;
    private final CommissionPlanRepository plans;
    private final QualityRepository quality;
    private final FactStore facts;
    private final JsonCodec json;

    StatementCalculator(PlanResolver planResolver, CommissionPlanRepository plans, QualityRepository quality,
                        FactStore facts, JsonCodec json) {
        this.planResolver = planResolver;
        this.plans = plans;
        this.quality = quality;
        this.facts = facts;
        this.json = json;
    }

    /**
     * Contexto do período: lançamentos considerados (de todos os beneficiários), equipes e membros.
     */
    record PeriodContext(LocalDate start, LocalDate end, List<CommissionLedgerEntry> entries, List<TeamStructure> teams,
                         Map<UUID, WorkforceMember> members) {
    }

    /** Regras a usar no lugar do plano vigente (simulação de versão). */
    record Override(ResolvedPlan plan) {
    }

    record Outcome(CommissionStatement.Calculation calculation, CalculationResult result) {
    }

    /** Memória gravada no extrato (JSON). */
    record CalculationMemory(int schema, UUID planId, String planCode, Integer planVersion, PlanRules rules,
                             LocalDate periodStart, LocalDate periodEnd, List<UUID> entryIds,
                             List<SegmentVolume> volumes, BigDecimal firstInstallmentWeight, int workedDays,
                             GoalInput goal, QualityInput quality, List<PoolShare> poolShares,
                             CalculationResult result) {
    }

    /** Destinatários do período: beneficiários individuais e quem recebe pool das equipes com crédito. */
    Set<UUID> recipients(PeriodContext ctx) {
        Set<UUID> users = new HashSet<>();
        Set<UUID> teamsWithCredit = new HashSet<>();
        for (CommissionLedgerEntry e : ctx.entries()) {
            if (e.getBeneficiaryType() == BeneficiaryType.USER) {
                users.add(e.getBeneficiaryId());
            } else {
                teamsWithCredit.add(e.getBeneficiaryId());
            }
        }
        for (TeamStructure team : ctx.teams()) {
            if (teamsWithCredit.contains(team.id())) {
                if (team.supervisorId() != null) {
                    users.add(team.supervisorId());
                }
                users.addAll(team.memberIds());
            }
        }
        return users;
    }

    Outcome calculate(UUID userId, PeriodContext ctx, Optional<Override> override) {
        WorkforceMember member = ctx.members().get(userId);
        Optional<ResolvedPlan> plan = override.map(Override::plan)
                .or(() -> member == null ? Optional.empty() : planResolver.forMember(member, ctx.start()));
        List<CommissionLedgerEntry> own = ctx.entries().stream()
                .filter(e -> e.getBeneficiaryType() == BeneficiaryType.USER && e.getBeneficiaryId().equals(userId)).toList();
        List<UUID> entryIds = own.stream().map(CommissionLedgerEntry::getId).toList();
        if (plan.isEmpty()) {
            Money credited = own.stream().map(CommissionLedgerEntry::getCreditedAmount).reduce(Money.ZERO, Money::plus);
            CalculationResult result = new CalculationResult(StatementOutcome.NO_PLAN, Money.ZERO, Money.ZERO, credited,
                    null, BigDecimal.ONE, BigDecimal.ZERO, List.of(new CalculationResult.Line(1,
                    CalculationResult.Component.ELIGIBILITY, "Nenhum plano publicado e vigente cobre o negociador no período",
                    null, credited, null, null, Money.ZERO)));
            String memory = json.toJson(new CalculationMemory(MEMORY_SCHEMA, null, null, null, null, ctx.start(), ctx.end(),
                    entryIds, List.of(), BigDecimal.ZERO, 0, null, null, List.of(), result));
            return new Outcome(new CommissionStatement.Calculation(CommissionStatement.PlanRef.NONE, StatementOutcome.NO_PLAN,
                    Money.ZERO, Money.ZERO, credited, lines(result), memory, MEMORY_SCHEMA), result);
        }
        PlanRules rules = plan.get().rules();
        boolean simulated = override.isPresent();
        List<SegmentVolume> volumes = volumes(own, rules, simulated);
        Instant from = ctx.start().atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        Instant to = ctx.end().plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();

        BigDecimal firstInstallmentWeight = rules.fixedPerAgreement() == null ? BigDecimal.ZERO
                : facts.firstInstallmentCreditWeight(userId, from, to);
        int workedDays = facts.workedDays(List.of(userId), ctx.start(), ctx.end()).getOrDefault(userId, 0);
        GoalInput goal = goal(userId, rules, volumes, ctx, from);
        QualityInput qualityInput = quality(userId, rules, ctx, from, to);
        List<PoolShare> pools = pools(userId, rules, ctx);

        EngineInput input = new EngineInput(rules, volumes, firstInstallmentWeight, workedDays, goal, qualityInput, pools);
        CalculationResult result = CommissionEngine.calculate(input);
        CommissionStatement.PlanRef ref = new CommissionStatement.PlanRef(plan.get().id(), plan.get().plan().getCode(),
                plan.get().plan().getPlanVersion());
        String memory = json.toJson(new CalculationMemory(MEMORY_SCHEMA, ref.planId(), ref.code(), ref.version(), rules,
                ctx.start(), ctx.end(), entryIds, volumes, firstInstallmentWeight, workedDays, goal, qualityInput, pools,
                result));
        return new Outcome(new CommissionStatement.Calculation(ref, result.outcome(), result.total(), result.withheld(),
                result.creditedRecovery(), lines(result), memory, MEMORY_SCHEMA), result);
    }

    /**
     * Volume líquido por segmento. Na simulação, o crédito de cada lançamento é recalculado com os
     * pesos da versão simulada (a base e a condição de renegociação/desligamento são as registradas).
     */
    private static List<SegmentVolume> volumes(List<CommissionLedgerEntry> entries, PlanRules rules, boolean simulated) {
        Map<Segment, Money> bySegment = new TreeMap<>();
        for (CommissionLedgerEntry e : entries) {
            Money credited = simulated ? simulatedCredit(e, rules) : e.getCreditedAmount();
            bySegment.merge(e.segment(), credited, Money::plus);
        }
        return bySegment.entrySet().stream().map(en -> new SegmentVolume(en.getKey(), en.getValue())).toList();
    }

    private static Money simulatedCredit(CommissionLedgerEntry e, PlanRules rules) {
        if (CommissionPostingService.NOTE_TERMINATED.equals(e.getCreditNote())) {
            return Money.ZERO;
        }
        BigDecimal weight = rules.attribution().weightOf(e.getOrigin()).divide(HUNDRED, 10, RoundingMode.HALF_EVEN);
        BigDecimal factor = e.getRenegotiationFactor().value().compareTo(HUNDRED) < 0
                ? rules.attribution().ownRenegotiationWeightPercent().divide(HUNDRED, 10, RoundingMode.HALF_EVEN)
                : BigDecimal.ONE;
        return e.getBaseAmount().times(weight.multiply(factor));
    }

    private GoalInput goal(UUID userId, PlanRules rules, List<SegmentVolume> volumes, PeriodContext ctx, Instant from) {
        if (rules.goal() == null) {
            return null;
        }
        boolean team = rules.goal().scope() == GoalScope.TEAM;
        Optional<TeamStructure> userTeam = team ? teamOf(userId, ctx.teams()) : Optional.empty();
        Optional<GoalTarget> periodGoal = team
                ? userTeam.flatMap(t -> plans.findGoal(ctx.start(), GoalTarget.SubjectType.TEAM, t.id()))
                : plans.findGoal(ctx.start(), GoalTarget.SubjectType.USER, userId);
        // Meta em unidade diferente da métrica do plano não é reinterpretada: vale a meta padrão.
        // Meta antiga sem unidade (anterior à V1806, não dedutível) segue a métrica do plano.
        GoalUnit expected = rules.goal().metric().unit();
        GoalUnit disregarded = periodGoal.map(GoalTarget::getUnit).filter(u -> u != expected).orElse(null);
        BigDecimal target = periodGoal.filter(g -> disregarded == null).map(GoalTarget::getTargetValue)
                .orElse(rules.goal().defaultTarget());
        if (target == null && disregarded == null) {
            return null;
        }
        Set<UUID> people = team && userTeam.isPresent() ? peopleOf(userTeam.get()) : Set.of(userId);
        Money recovered = team ? ctx.entries().stream()
                .filter(e -> e.getBeneficiaryType() == BeneficiaryType.USER && people.contains(e.getBeneficiaryId()))
                .map(CommissionLedgerEntry::getCreditedAmount).reduce(Money.ZERO, Money::plus)
                : volumes.stream().map(SegmentVolume::amount).reduce(Money.ZERO, Money::plus);
        if (rules.goal().metric() == GoalMetric.RECOVERED) {
            return new GoalInput(target, recovered.amount(), disregarded);
        }
        BigDecimal base = facts.eligibleBalanceAt(people, from, rules.eligibility().creditorIds());
        BigDecimal rate = base.signum() == 0 ? BigDecimal.ZERO
                : recovered.amount().multiply(HUNDRED).divide(base, 4, RoundingMode.HALF_EVEN);
        return new GoalInput(target, rate, disregarded);
    }

    private QualityInput quality(UUID userId, PlanRules rules, PeriodContext ctx, Instant from, Instant to) {
        int safraAgreements = 0;
        int safraBreaks = 0;
        if (rules.quality().earlyBreak() != null) {
            int window = rules.quality().earlyBreak().windowDays();
            FactStore.Safra safra = facts.safra(userId, from.minus(java.time.Duration.ofDays(window)),
                    to.minus(java.time.Duration.ofDays(window)), window);
            safraAgreements = safra.agreements();
            safraBreaks = safra.earlyBreaks();
        }
        FactStore.EnvelopeUsage envelope = rules.quality().envelope() == null ? new FactStore.EnvelopeUsage(0, null)
                : facts.envelopeUsage(userId, from, to);
        List<QualityEvaluation> evaluations = quality.findEvaluations(userId, ctx.start(), ctx.end());
        BigDecimal average = evaluations.isEmpty() ? null : evaluations.stream().map(QualityEvaluation::getScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(evaluations.size()), 2, RoundingMode.HALF_EVEN);
        int severe = (int) quality.findOccurrences(userId, ctx.start(), ctx.end()).stream()
                .filter(o -> o.getType() == OccurrenceType.SEVERE_COMPLIANCE_VIOLATION
                        && o.getStatus() == ConductOccurrence.Status.CONFIRMED).count();
        return new QualityInput(safraAgreements, safraBreaks, envelope.sample(), envelope.average(), evaluations.size(),
                average, severe);
    }

    /** Participações do usuário nos pools das equipes (supervisor; membros se a distribuição for igualitária). */
    private static List<PoolShare> pools(UUID userId, PlanRules rules, PeriodContext ctx) {
        if (rules.teamPool() == null) {
            return List.of();
        }
        List<PoolShare> shares = new ArrayList<>();
        for (TeamStructure team : ctx.teams()) {
            boolean supervisor = userId.equals(team.supervisorId());
            boolean member = team.memberIds().contains(userId);
            boolean equal = rules.teamPool().distribution() == PoolDistribution.SUPERVISOR_AND_MEMBERS_EQUALLY;
            if (!supervisor && !(equal && member)) {
                continue;
            }
            Money recovered = ctx.entries().stream()
                    .filter(e -> e.getBeneficiaryType() == BeneficiaryType.TEAM && e.getBeneficiaryId().equals(team.id()))
                    .filter(e -> rules.teamPool().includeAssistantAutonomous()
                            || e.getOrigin() != AgreementOrigin.ASSISTANT_AUTONOMOUS)
                    .map(CommissionLedgerEntry::getCreditedAmount).reduce(Money.ZERO, Money::plus);
            if (recovered.isZero()) {
                continue;
            }
            int divisor = equal ? activeParticipants(team, ctx.members()) : 1;
            shares.add(new PoolShare(team.id(), recovered, Math.max(1, divisor)));
        }
        return shares;
    }

    private static int activeParticipants(TeamStructure team, Map<UUID, WorkforceMember> members) {
        Set<UUID> people = peopleOf(team);
        return (int) people.stream().filter(id -> {
            WorkforceMember m = members.get(id);
            return m == null || m.active();
        }).count();
    }

    private static Set<UUID> peopleOf(TeamStructure team) {
        Set<UUID> people = new HashSet<>(team.memberIds());
        if (team.supervisorId() != null) {
            people.add(team.supervisorId());
        }
        return people;
    }

    private static Optional<TeamStructure> teamOf(UUID userId, List<TeamStructure> teams) {
        return teams.stream().filter(t -> userId.equals(t.supervisorId())).findFirst()
                .or(() -> teams.stream().filter(t -> t.memberIds().contains(userId)).findFirst());
    }

    static List<StatementLine> lines(CalculationResult result) {
        return result.lines().stream().map(l -> new StatementLine(l.number(), l.component().name(), l.description(),
                l.segmentKey(), l.base(), l.rate(), l.factor(), l.amount())).toList();
    }

    /** Usuários envolvidos no contexto (para carregar membros de uma vez). */
    static Set<UUID> involvedUsers(Collection<UUID> recipients, List<TeamStructure> teams) {
        Set<UUID> users = new HashSet<>(recipients);
        teams.forEach(t -> users.addAll(peopleOf(t)));
        return users.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
    }
}
