package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries.TeamStructure;
import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries.WorkforceMember;
import br.com.gestorcobranca.incentive.application.port.in.CommissionPlanUseCases;
import br.com.gestorcobranca.incentive.application.port.in.IncentiveProvisioning;
import br.com.gestorcobranca.incentive.application.service.PlanResolver.ResolvedPlan;
import br.com.gestorcobranca.incentive.application.service.StatementCalculator.Outcome;
import br.com.gestorcobranca.incentive.application.service.StatementCalculator.PeriodContext;
import br.com.gestorcobranca.incentive.domain.model.ledger.CommissionLedgerEntry;
import br.com.gestorcobranca.incentive.domain.model.ledger.CommissionPeriod;
import br.com.gestorcobranca.incentive.domain.model.plan.CommissionPlan;
import br.com.gestorcobranca.incentive.domain.model.plan.GoalTarget;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanStatus;
import br.com.gestorcobranca.incentive.domain.model.plan.StandardPlans;
import br.com.gestorcobranca.incentive.domain.model.statement.CommissionStatement;
import br.com.gestorcobranca.incentive.domain.repository.CommissionPlanRepository;
import br.com.gestorcobranca.incentive.domain.repository.LedgerRepository;
import br.com.gestorcobranca.incentive.domain.repository.StatementRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class CommissionPlanService implements CommissionPlanUseCases, IncentiveProvisioning {

    private final CommissionPlanRepository plans;
    private final LedgerRepository ledger;
    private final StatementRepository statements;
    private final PlanRulesCodec codec;
    private final StatementCalculator calculator;
    private final Workforce workforce;
    private final IncentiveAccess access;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final Clock clock;

    CommissionPlanService(CommissionPlanRepository plans, LedgerRepository ledger, StatementRepository statements,
                          PlanRulesCodec codec, StatementCalculator calculator, Workforce workforce,
                          IncentiveAccess access, AuditTrail auditTrail, BusinessEventLogger businessEvents, Clock clock) {
        this.plans = plans;
        this.ledger = ledger;
        this.statements = statements;
        this.codec = codec;
        this.calculator = calculator;
        this.workforce = workforce;
        this.access = access;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.clock = clock;
    }

    @Override
    public PlanView createDraft(PlanCommand command) {
        access.requirePermission(Permission.COMMISSION_PLAN_MANAGE);
        Guard.require(command.rules() != null, "plan.rules_required", "Regras obrigatórias");
        String code = command.code() == null ? null : command.code().trim().toUpperCase();
        if (code != null && plans.existsByCode(code)) {
            throw new ConflictException("plan.code_taken", "Já existe plano com este código; crie uma nova versão");
        }
        CommissionPlan plan = plans.save(new CommissionPlan(code, 1, command.name(), command.description(),
                command.priority(), codec.encode(command.rules()), PlanRules.SCHEMA_VERSION));
        auditTrail.record(AuditEntry.of("commission_plan.created", "commission_plan", plan.getId())
                .created("code", plan.getCode()).created("version", plan.getPlanVersion())
                .created("name", plan.getName()).created("rules", plan.getRules()));
        return view(plan);
    }

    @Override
    public PlanView updateDraft(UUID planId, PlanCommand command) {
        access.requirePermission(Permission.COMMISSION_PLAN_MANAGE);
        CommissionPlan plan = load(planId);
        Guard.require(command.rules() != null, "plan.rules_required", "Regras obrigatórias");
        String rulesBefore = plan.getRules();
        AuditEntry audit = AuditEntry.of("commission_plan.updated", "commission_plan", planId)
                .change("name", plan.getName(), command.name()).change("priority", plan.getPriority(), command.priority());
        plan.describe(command.name(), command.description(), command.priority());
        plan.defineRules(codec.encode(command.rules()), PlanRules.SCHEMA_VERSION);
        plans.save(plan);
        auditTrail.record(audit.change("rules", rulesBefore, plan.getRules()));
        return view(plan);
    }

    @Override
    public PlanView newVersion(UUID planId) {
        access.requirePermission(Permission.COMMISSION_PLAN_MANAGE);
        CommissionPlan source = load(planId);
        List<CommissionPlan> versions = plans.findByCodeOrderByPlanVersionDesc(source.getCode());
        Guard.requireState(versions.stream().noneMatch(p -> p.getStatus() == PlanStatus.DRAFT), "plan.draft_exists",
                "Já existe um rascunho deste plano");
        CommissionPlan draft = plans.save(new CommissionPlan(source.getCode(), versions.getFirst().getPlanVersion() + 1,
                source.getName(), source.getDescription(), source.getPriority(), codec.encode(codec.decode(source)),
                PlanRules.SCHEMA_VERSION));
        auditTrail.record(AuditEntry.of("commission_plan.version_created", "commission_plan", draft.getId())
                .created("code", draft.getCode()).created("version", draft.getPlanVersion())
                .meta("sourcePlanId", source.getId()));
        return view(draft);
    }

    @Override
    public PlanView publish(UUID planId, LocalDate effectiveFrom) {
        access.requirePermission(Permission.COMMISSION_PLAN_MANAGE);
        return view(publishPlan(load(planId), effectiveFrom));
    }

    private CommissionPlan publishPlan(CommissionPlan plan, LocalDate effectiveFrom) {
        codec.decode(plan);
        LocalDate earliest = earliestStart();
        List<CommissionPlan> published = plans.findByCodeOrderByPlanVersionDesc(plan.getCode()).stream()
                .filter(p -> p.getStatus() == PlanStatus.PUBLISHED && !p.getId().equals(plan.getId())).toList();
        plan.publish(effectiveFrom, earliest, access.actor().id(), Instant.now(clock));
        for (CommissionPlan previous : published) {
            if (previous.getEffectiveTo() == null || !previous.getEffectiveTo().isBefore(effectiveFrom)) {
                previous.supersede(effectiveFrom);
                plans.save(previous);
                auditTrail.record(AuditEntry.of("commission_plan.superseded", "commission_plan", previous.getId())
                        .change("effectiveTo", null, previous.getEffectiveTo()).meta("byPlanId", plan.getId()));
            }
        }
        plans.save(plan);
        PlanRules rules = codec.decode(plan);
        auditTrail.record(AuditEntry.of("commission_plan.published", "commission_plan", plan.getId())
                .change("status", PlanStatus.DRAFT, PlanStatus.PUBLISHED)
                .created("effectiveFrom", plan.getEffectiveFrom()).meta("code", plan.getCode())
                .meta("version", plan.getPlanVersion()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.COMMISSION_PLAN_PUBLISHED)
                .reference("commission_plan", plan.getId())
                .attr("code", plan.getCode()).attr("version", plan.getPlanVersion())
                .attr("effectiveFrom", plan.getEffectiveFrom().toString())
                .attr("tierMode", rules.recoveryRate() == null ? null : rules.recoveryRate().mode())
                .attr("hasGoal", rules.goal() != null).attr("hasPool", rules.teamPool() != null)
                .attr("hasFixedPerAgreement", rules.fixedPerAgreement() != null)
                .attr("graceBusinessDays", rules.clearing().graceBusinessDays())
                .attr("clawbackEnabled", rules.clearing().clawbackEnabled())
                .attr("creditModel", rules.attribution().creditModel()));
        return plan;
    }

    @Override
    public PlanView retire(UUID planId, LocalDate effectiveTo) {
        access.requirePermission(Permission.COMMISSION_PLAN_MANAGE);
        CommissionPlan plan = load(planId);
        LocalDate before = plan.getEffectiveTo();
        LocalDate earliestEnd = earliestStart().minusDays(1);
        plan.retire(effectiveTo, earliestEnd, access.actor().id(), Instant.now(clock));
        plans.save(plan);
        auditTrail.record(AuditEntry.of("commission_plan.retired", "commission_plan", planId)
                .change("status", PlanStatus.PUBLISHED, PlanStatus.RETIRED).change("effectiveTo", before, plan.getEffectiveTo()));
        return view(plan);
    }

    @Override
    @Transactional(readOnly = true)
    public PlanView get(UUID planId) {
        access.requirePermission(Permission.COMMISSION_PLAN_MANAGE);
        return view(load(planId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PlanView> list() {
        access.requirePermission(Permission.COMMISSION_PLAN_MANAGE);
        return plans.findAllOrdered().stream().map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SimulationView simulate(UUID planId, YearMonth period) {
        access.requirePermission(Permission.COMMISSION_PLAN_MANAGE);
        Guard.require(period != null && period.isBefore(YearMonth.from(today())), "simulation.period_invalid",
                "Simule sobre um período já encerrado");
        CommissionPlan plan = load(planId);
        ResolvedPlan candidate = new ResolvedPlan(plan, codec.decode(plan));
        LocalDate start = period.atDay(1);
        List<CommissionLedgerEntry> entries = ledger.findCleared(start);
        List<TeamStructure> teams = workforce.teams();
        PeriodContext seed = new PeriodContext(start, period.atEndOfMonth(), entries, teams, Map.of());
        Set<UUID> recipients = calculator.recipients(seed);
        PeriodContext ctx = new PeriodContext(start, period.atEndOfMonth(), entries, teams,
                workforce.members(StatementCalculator.involvedUsers(recipients, teams)));
        List<SimulatedStatement> rows = new ArrayList<>();
        Money simulatedTotal = Money.ZERO;
        Money actualTotal = Money.ZERO;
        Money credited = Money.ZERO;
        for (UUID userId : recipients.stream().sorted().toList()) {
            WorkforceMember member = ctx.members().get(userId);
            Optional<CommissionStatement> actual = statements.findByPeriodAndBeneficiary(start, userId);
            Money actualAmount = actual.map(CommissionStatement::getPayableAmount).orElse(Money.ZERO);
            boolean covered = member != null && candidate.rules().eligibility().coversMember(member.roleCodes(),
                    member.teamIds());
            if (!covered) {
                rows.add(new SimulatedStatement(userId, "NOT_COVERED", BigDecimal.ZERO, BigDecimal.ZERO, actualAmount.amount()));
                actualTotal = actualTotal.plus(actualAmount);
                continue;
            }
            Outcome outcome = calculator.calculate(userId, ctx, Optional.of(new StatementCalculator.Override(candidate)));
            Money simulated = outcome.calculation().calculated();
            rows.add(new SimulatedStatement(userId, outcome.calculation().outcome().name(),
                    outcome.result().creditedRecovery().amount(), simulated.amount(), actualAmount.amount()));
            simulatedTotal = simulatedTotal.plus(simulated);
            actualTotal = actualTotal.plus(actualAmount);
            credited = credited.plus(outcome.result().creditedRecovery());
        }
        access.logCompensationListing("commission_simulation", "plan:" + planId + ":" + period);
        return new SimulationView(plan.getId(), plan.getCode(), plan.getPlanVersion(), period, rows,
                simulatedTotal.amount(), actualTotal.amount(), credited.amount(), ratio(simulatedTotal, credited),
                ratio(actualTotal, credited));
    }

    @Override
    public GoalView setGoal(GoalCommand command) {
        access.requirePermission(Permission.COMMISSION_PLAN_MANAGE);
        Guard.require(command.period() != null, "goal.period_required", "Informe o período");
        LocalDate start = command.period().atDay(1);
        Guard.requireState(ledger.findPeriod(start).map(p -> !p.isClosed()).orElse(true), "goal.period_closed",
                "Período já fechado");
        GoalTarget.SubjectType type = StatementService.enumOf(GoalTarget.SubjectType.class,
                command.subjectType() == null ? "" : command.subjectType(), "goal.subject_type_invalid");
        Optional<GoalTarget> existing = plans.findGoal(start, type, command.subjectId());
        BigDecimal before = existing.map(GoalTarget::getTargetValue).orElse(null);
        GoalTarget goal = existing.orElseGet(() -> new GoalTarget(start, type, command.subjectId(), command.target()));
        goal.changeTarget(command.target());
        plans.saveGoal(goal);
        auditTrail.record(AuditEntry.of("commission_goal.defined", "commission_goal", goal.getId())
                .change("target", before, goal.getTargetValue()).meta("period", command.period().toString())
                .meta("subjectType", type).meta("subjectId", command.subjectId()));
        return goalView(goal);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GoalView> goals(YearMonth period) {
        access.requirePermission(Permission.COMMISSION_PLAN_MANAGE);
        return plans.findGoals(period.atDay(1)).stream().map(CommissionPlanService::goalView).toList();
    }

    @Override
    public boolean ensureDefaultPlan() {
        if (plans.existsByCode(StandardPlans.PADRAO_MERCADO)) {
            return false;
        }
        CommissionPlan plan = plans.save(new CommissionPlan(StandardPlans.PADRAO_MERCADO, 1, "Padrão de mercado",
                "Plano de referência semeado no provisionamento (§4.3 do desenho); calibrar com a operação.", 0,
                codec.encode(StandardPlans.padraoMercado()), PlanRules.SCHEMA_VERSION));
        auditTrail.record(AuditEntry.of("commission_plan.seeded", "commission_plan", plan.getId())
                .created("code", plan.getCode()).created("version", plan.getPlanVersion()));
        publishPlan(plan, earliestStart());
        return true;
    }

    /** Primeiro dia aceito para nova vigência: mês corrente e depois do último período fechado. */
    private LocalDate earliestStart() {
        LocalDate currentMonth = today().withDayOfMonth(1);
        return ledger.findLatestClosedPeriod().map(CommissionPeriod::getPeriodEnd).map(d -> d.plusDays(1))
                .filter(d -> d.isAfter(currentMonth)).orElse(currentMonth);
    }

    private CommissionPlan load(UUID planId) {
        return plans.findById(planId).orElseThrow(() -> ResourceNotFoundException.of("commission_plan", planId));
    }

    private PlanView view(CommissionPlan p) {
        return new PlanView(p.getId(), p.getCode(), p.getPlanVersion(), p.getName(), p.getDescription(), p.getStatus().name(),
                p.getPriority(), p.getEffectiveFrom(), p.getEffectiveTo(), codec.decode(p), p.getRulesSchemaVersion(),
                p.getPublishedAt(), p.getPublishedBy());
    }

    private static GoalView goalView(GoalTarget g) {
        return new GoalView(g.getId(), YearMonth.from(g.getPeriodStart()), g.getSubjectType().name(), g.getSubjectId(),
                g.getTargetValue());
    }

    private static BigDecimal ratio(Money amount, Money base) {
        return base.isPositive() ? amount.amount().divide(base.amount(), 6, RoundingMode.HALF_EVEN) : null;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }
}
