package br.com.gestorcobranca.incentive.domain.service;

import br.com.gestorcobranca.incentive.domain.model.DelayBucket;
import br.com.gestorcobranca.incentive.domain.model.Segment;
import br.com.gestorcobranca.incentive.domain.model.plan.AcceleratorMode;
import br.com.gestorcobranca.incentive.domain.model.plan.GoalMetric;
import br.com.gestorcobranca.incentive.domain.model.plan.GoalScope;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.EarlyBreakDeflator;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.Eligibility;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.EnvelopeDeflator;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.FixedPerAgreementComponent;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.GoalComponent;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.Limits;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.MonitoringGate;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.QualityModifiers;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.RateTable;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.RateTier;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.RecoveryRateComponent;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.TeamPoolComponent;
import br.com.gestorcobranca.incentive.domain.model.plan.PoolDistribution;
import br.com.gestorcobranca.incentive.domain.model.plan.SegmentSelector;
import br.com.gestorcobranca.incentive.domain.model.plan.StandardPlans;
import br.com.gestorcobranca.incentive.domain.model.plan.TierMode;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementOutcome;
import br.com.gestorcobranca.incentive.domain.service.CalculationResult.Component;
import br.com.gestorcobranca.incentive.domain.service.EngineInput.GoalInput;
import br.com.gestorcobranca.incentive.domain.service.EngineInput.PoolShare;
import br.com.gestorcobranca.incentive.domain.service.EngineInput.QualityInput;
import br.com.gestorcobranca.incentive.domain.service.EngineInput.SegmentVolume;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Casos numéricos do motor verificados à mão. Plano base: faixas 1,5% até 50 mil, 2,0% de 50 a
 * 100 mil, 2,5% acima; sem meta, deflatores, portão ou pool, salvo quando o caso os habilita.
 */
class CommissionEngineTest {

    private static final UUID CREDITOR = UUID.fromString("00000000-0000-0000-0000-00000000c001");
    private static final UUID OTHER_CREDITOR = UUID.fromString("00000000-0000-0000-0000-00000000c002");
    private static final UUID TEAM = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static final List<RateTier> TIERS = List.of(
            new RateTier(BigDecimal.ZERO, new BigDecimal("1.5")),
            new RateTier(new BigDecimal("50000"), new BigDecimal("2.0")),
            new RateTier(new BigDecimal("100000"), new BigDecimal("2.5")));

    // ------------------------------------------------------------------ construtores de cenário

    private static PlanRules base() {
        PlanRules standard = StandardPlans.padraoMercado();
        return new PlanRules(new Eligibility(Set.of(), Set.of(), Set.of(), Set.of(), 0),
                new RecoveryRateComponent(TierMode.PROGRESSIVE, List.of(new RateTable(SegmentSelector.ANY, TIERS))),
                null, null, List.of(), new QualityModifiers(null, null, null, false), standard.attribution(), null,
                new Limits(null), standard.clearing());
    }

    private static PlanRules with(PlanRules r, RecoveryRateComponent rate) {
        return new PlanRules(r.eligibility(), rate, r.fixedPerAgreement(), r.goal(), r.difficultyMultipliers(), r.quality(),
                r.attribution(), r.teamPool(), r.limits(), r.clearing());
    }

    private static PlanRules withGoal(PlanRules r, AcceleratorMode mode, String cap) {
        GoalComponent goal = new GoalComponent(GoalMetric.RECOVERED, GoalScope.INDIVIDUAL, null, new BigDecimal("80"),
                new BigDecimal("0.5"), new BigDecimal("110"), new BigDecimal("1.2"), mode,
                cap == null ? null : new BigDecimal(cap));
        return new PlanRules(r.eligibility(), r.recoveryRate(), r.fixedPerAgreement(), goal, r.difficultyMultipliers(),
                r.quality(), r.attribution(), r.teamPool(), r.limits(), r.clearing());
    }

    private static PlanRules withQuality(PlanRules r, QualityModifiers quality) {
        return new PlanRules(r.eligibility(), r.recoveryRate(), r.fixedPerAgreement(), r.goal(), r.difficultyMultipliers(),
                quality, r.attribution(), r.teamPool(), r.limits(), r.clearing());
    }

    private static PlanRules withMultipliers(PlanRules r) {
        return new PlanRules(r.eligibility(), r.recoveryRate(), r.fixedPerAgreement(), r.goal(),
                StandardPlans.padraoMercado().difficultyMultipliers(), r.quality(), r.attribution(), r.teamPool(),
                r.limits(), r.clearing());
    }

    private static Segment segment(DelayBucket bucket) {
        return new Segment(CREDITOR, null, bucket);
    }

    private static SegmentVolume volume(DelayBucket bucket, String amount) {
        return new SegmentVolume(segment(bucket), Money.of(amount));
    }

    private static CalculationResult run(PlanRules rules, List<SegmentVolume> volumes) {
        return CommissionEngine.calculate(new EngineInput(rules, volumes, BigDecimal.ZERO, 22, null, null, null));
    }

    private static CalculationResult run(PlanRules rules, List<SegmentVolume> volumes, GoalInput goal, QualityInput quality) {
        return CommissionEngine.calculate(new EngineInput(rules, volumes, BigDecimal.ZERO, 22, goal, quality, null));
    }

    private static GoalInput goal(String target, String actual) {
        return new GoalInput(new BigDecimal(target), new BigDecimal(actual));
    }

    private static Money brl(String amount) {
        return Money.of(amount);
    }

    // ------------------------------------------------------------------ percentual sobre recuperado


    @Test
    void progressiveTiersPayEachSliceAtItsRate() {
        // 50.000 × 1,5% + 50.000 × 2,0% + 20.000 × 2,5% = 750 + 1.000 + 500
        CalculationResult result = run(base(), List.of(volume(DelayBucket.D31_90, "120000")));
        assertThat(result.total()).isEqualTo(brl("2250.00"));
        assertThat(result.outcome()).isEqualTo(StatementOutcome.PAYABLE);
        assertThat(result.lines()).extracting(CalculationResult.Line::component).containsExactly(Component.RATE);
        assertThat(result.lines().getFirst().rate()).isEqualByComparingTo("1.875");
    }

    @Test
    void progressiveTierBoundariesBelongToTheLowerTier() {
        assertThat(run(base(), List.of(volume(DelayBucket.D31_90, "50000"))).total()).isEqualTo(brl("750.00"));
        // 750 + 50.000 × 2%
        assertThat(run(base(), List.of(volume(DelayBucket.D31_90, "100000"))).total()).isEqualTo(brl("1750.00"));
    }

    @Test
    void reachedTierAppliesTheRateOfTheReachedTierToTheWholeVolume() {
        PlanRules rules = with(base(), new RecoveryRateComponent(TierMode.REACHED_TIER,
                List.of(new RateTable(SegmentSelector.ANY, TIERS))));
        assertThat(run(rules, List.of(volume(DelayBucket.D31_90, "120000"))).total()).isEqualTo(brl("3000.00"));
        assertThat(run(rules, List.of(volume(DelayBucket.D31_90, "50000"))).total()).isEqualTo(brl("750.00"));
        // 50.000,01 × 2% = 1.000,0002
        assertThat(run(rules, List.of(volume(DelayBucket.D31_90, "50000.01"))).total()).isEqualTo(brl("1000.00"));
    }

    @Test
    void difficultyMultiplierAppliesOnTheProportionalShareOfTheTableCommission() {
        // volume 120.000 → 2.250 (taxa efetiva 1,875%); 40.000 × 1,875% × 1,0 = 750; 80.000 × 1,875% × 1,4 = 2.100
        CalculationResult result = run(withMultipliers(base()),
                List.of(volume(DelayBucket.D31_90, "40000"), volume(DelayBucket.D181_360, "80000")));
        assertThat(result.total()).isEqualTo(brl("2850.00"));
        assertThat(result.lines()).extracting(CalculationResult.Line::amount)
                .containsExactlyInAnyOrder(brl("750.00"), brl("2100.00"));
    }

    @Test
    void mostSpecificTableWinsPerSegment() {
        RateTable creditorTable = new RateTable(new SegmentSelector(Set.of(OTHER_CREDITOR), Set.of(), Set.of()),
                List.of(new RateTier(BigDecimal.ZERO, new BigDecimal("3"))));
        PlanRules rules = with(base(), new RecoveryRateComponent(TierMode.PROGRESSIVE,
                List.of(new RateTable(SegmentSelector.ANY, TIERS), creditorTable)));
        // outro credor: 10.000 × 3% = 300; credor padrão: 20.000 × 1,5% = 300
        CalculationResult result = run(rules, List.of(
                new SegmentVolume(new Segment(OTHER_CREDITOR, null, DelayBucket.D0_30), brl("10000")),
                volume(DelayBucket.D0_30, "20000")));
        assertThat(result.total()).isEqualTo(brl("600.00"));
    }

    @Test
    void segmentsOutsideThePlanCoverageDoNotPay() {
        PlanRules r = base();
        PlanRules rules = new PlanRules(new Eligibility(Set.of(), Set.of(), Set.of(CREDITOR), Set.of(), 0),
                r.recoveryRate(), null, null, List.of(), r.quality(), r.attribution(), null, r.limits(), r.clearing());
        CalculationResult result = run(rules, List.of(volume(DelayBucket.D0_30, "10000"),
                new SegmentVolume(new Segment(OTHER_CREDITOR, null, DelayBucket.D0_30), brl("90000"))));
        assertThat(result.total()).isEqualTo(brl("150.00"));
        assertThat(result.lines()).anyMatch(l -> l.component() == Component.COVERAGE && l.amount().isZero());
    }

    @Test
    void netNegativeVolumeFromClawbackPaysNothing() {
        CalculationResult result = run(base(), List.of(volume(DelayBucket.D0_30, "-5000")));
        assertThat(result.total()).isEqualTo(Money.ZERO);
    }

    @Test
    void negativeSegmentWithHigherMultiplierIsFlooredAtZero() {
        // volume 10 → 0,15 (1,5%); +100 × 1,5% × 1,0 = 1,50; −90 × 1,5% × 1,8 = −2,43; total −0,93 → piso 0
        CalculationResult result = run(withMultipliers(base()),
                List.of(volume(DelayBucket.D0_30, "100"), volume(DelayBucket.D361_PLUS, "-90")));
        assertThat(result.total()).isEqualTo(Money.ZERO);
        assertThat(result.lines()).anyMatch(l -> l.component() == Component.FLOOR && l.amount().equals(brl("0.93")));
    }

    // ------------------------------------------------------------------ fixo, meta, deflatores

    @Test
    void fixedPerAgreementUsesTheCreditFractionOfEachAgreement() {
        PlanRules r = base();
        PlanRules rules = new PlanRules(r.eligibility(), null, new FixedPerAgreementComponent(new BigDecimal("50.00")), null,
                List.of(), r.quality(), r.attribution(), null, r.limits(), r.clearing());
        // 3 acordos com pesos 100%, 30% e 50% → 1,8 × R$ 50 = R$ 90
        CalculationResult result = CommissionEngine.calculate(
                new EngineInput(rules, List.of(), new BigDecimal("1.8"), 22, null, null, null));
        assertThat(result.total()).isEqualTo(brl("90.00"));
    }


    @Test
    void belowTriggerPaysTheReducedFactor() {
        // 70.000 → 750 + 400 = 1.150; 70% < 80% → × 0,5 = 575
        CalculationResult result = run(withGoal(base(), AcceleratorMode.TOTAL, "300"),
                List.of(volume(DelayBucket.D0_30, "70000")), goal("100000", "70000"), null);
        assertThat(result.total()).isEqualTo(brl("575.00"));
        assertThat(result.goalFactor()).isEqualByComparingTo("0.5");
        assertThat(result.attainmentPercent()).isEqualByComparingTo("70");
    }

    @Test
    void betweenTriggerAndAcceleratorPaysFactorOne() {
        CalculationResult result = run(withGoal(base(), AcceleratorMode.TOTAL, "300"),
                List.of(volume(DelayBucket.D0_30, "100000")), goal("100000", "100000"), null);
        assertThat(result.total()).isEqualTo(brl("1750.00"));
    }

    @Test
    void acceleratorOnTotalMultipliesTheWholeVariable() {
        // 120% > 110% → 2.250 × 1,2 = 2.700
        CalculationResult result = run(withGoal(base(), AcceleratorMode.TOTAL, "300"),
                List.of(volume(DelayBucket.D0_30, "120000")), goal("100000", "120000"), null);
        assertThat(result.total()).isEqualTo(brl("2700.00"));
    }

    @Test
    void acceleratorOnExcessOnlyAcceleratesTheShareAboveTheThreshold() {
        // parcela excedente = (120 − 110) ÷ 120; 2.250 × 0,0833333333 × 0,2 = 37,4999… → 37,50
        CalculationResult result = run(withGoal(base(), AcceleratorMode.EXCESS, "300"),
                List.of(volume(DelayBucket.D0_30, "120000")), goal("100000", "120000"), null);
        assertThat(result.total()).isEqualTo(brl("2287.50"));
    }

    @Test
    void capLimitsTheRemuneratedVolumeToCapTimesTarget() {
        // meta 30.000, realizado 120.000 (400%) > teto 300% → volume × 0,75 = 90.000 → 750 + 800 = 1.550;
        // acelerador sobre o total: × 1,2 = 1.860
        CalculationResult result = run(withGoal(base(), AcceleratorMode.TOTAL, "300"),
                List.of(volume(DelayBucket.D0_30, "120000")), goal("30000", "120000"), null);
        assertThat(result.total()).isEqualTo(brl("1860.00"));
        assertThat(result.lines()).anyMatch(l -> l.component() == Component.GOAL_CAP);
    }

    @Test
    void withoutTargetTheGoalIsNotApplied() {
        CalculationResult result = run(withGoal(base(), AcceleratorMode.TOTAL, "300"),
                List.of(volume(DelayBucket.D0_30, "70000")), null, null);
        assertThat(result.total()).isEqualTo(brl("1150.00"));
        assertThat(result.attainmentPercent()).isNull();
    }


    private final QualityModifiers deflators = new QualityModifiers(
            new EarlyBreakDeflator(60, new BigDecimal("35"), new BigDecimal("20"), 10),
            new EnvelopeDeflator(new BigDecimal("85"), new BigDecimal("10"), 10), null, false);

    @Test
    void deflatorsAreAddedAndAppliedOnTheVariableAfterGoal() {
        // quebra 8/20 = 40% > 35% → −20%; envelope 90% > 85% → −10%; 2.250 × (1 − 0,30) = 1.575
        CalculationResult result = run(withQuality(base(), deflators), List.of(volume(DelayBucket.D0_30, "120000")),
                null, new QualityInput(20, 8, 12, new BigDecimal("90"), 0, null, 0));
        assertThat(result.total()).isEqualTo(brl("1575.00"));
        assertThat(result.deflatorPercent()).isEqualByComparingTo("30");
    }

    @Test
    void deflatorsRequireTheMinimumSampleAndStrictlyExceedTheThreshold() {
        // safra de 5 acordos (< 10) e envelope exatamente no limite (85%): sem deflator
        CalculationResult result = run(withQuality(base(), deflators), List.of(volume(DelayBucket.D0_30, "120000")),
                null, new QualityInput(5, 5, 12, new BigDecimal("85"), 0, null, 0));
        assertThat(result.total()).isEqualTo(brl("2250.00"));
    }

    @Test
    void monitoringGateSuspendsTheVariableBelowMinimumScore() {
        QualityModifiers gate = new QualityModifiers(null, null, new MonitoringGate(new BigDecimal("7.0"), false), true);
        CalculationResult suspended = run(withQuality(base(), gate), List.of(volume(DelayBucket.D0_30, "120000")),
                null, new QualityInput(0, 0, 0, null, 3, new BigDecimal("6.50"), 0));
        assertThat(suspended.total()).isEqualTo(Money.ZERO);
        assertThat(suspended.withheld()).isEqualTo(brl("2250.00"));
        assertThat(suspended.outcome()).isEqualTo(StatementOutcome.SUSPENDED_QUALITY_GATE);

        CalculationResult atMinimum = run(withQuality(base(), gate), List.of(volume(DelayBucket.D0_30, "120000")),
                null, new QualityInput(0, 0, 0, null, 3, new BigDecimal("7.00"), 0));
        assertThat(atMinimum.total()).isEqualTo(brl("2250.00"));
    }

    @Test
    void monitoringGateWithoutEvaluationFollowsThePlan() {
        QualityModifiers strict = new QualityModifiers(null, null, new MonitoringGate(new BigDecimal("7.0"), true), false);
        assertThat(run(withQuality(base(), strict), List.of(volume(DelayBucket.D0_30, "10000")), null,
                QualityInput.NONE).outcome()).isEqualTo(StatementOutcome.SUSPENDED_QUALITY_GATE);
        QualityModifiers lenient = new QualityModifiers(null, null, new MonitoringGate(new BigDecimal("7.0"), false), false);
        assertThat(run(withQuality(base(), lenient), List.of(volume(DelayBucket.D0_30, "10000")), null,
                QualityInput.NONE).total()).isEqualTo(brl("150.00"));
    }

    @Test
    void severeComplianceViolationZeroesTheVariableAndPrevailsOverTheGate() {
        QualityModifiers rules = new QualityModifiers(null, null, new MonitoringGate(new BigDecimal("7.0"), false), true);
        CalculationResult result = run(withQuality(base(), rules), List.of(volume(DelayBucket.D0_30, "120000")),
                null, new QualityInput(0, 0, 0, null, 2, new BigDecimal("5"), 1));
        assertThat(result.total()).isEqualTo(Money.ZERO);
        assertThat(result.outcome()).isEqualTo(StatementOutcome.ZEROED_COMPLIANCE);
        assertThat(result.withheld()).isEqualTo(Money.ZERO);
    }

    // ------------------------------------------------------------------ pool, elegibilidade, teto

    @Test
    void teamPoolPaysRateOverTeamRecoveryDividedByTheParticipants() {
        PlanRules r = base();
        PlanRules rules = new PlanRules(r.eligibility(), null, null, null, List.of(), r.quality(), r.attribution(),
                new TeamPoolComponent(new BigDecimal("0.3"), true, PoolDistribution.SUPERVISOR), r.limits(), r.clearing());
        // 200.000 × 0,3% = 600; dividido por 4 = 150
        assertThat(CommissionEngine.calculate(new EngineInput(rules, List.of(), BigDecimal.ZERO, 22, null, null,
                List.of(new PoolShare(TEAM, brl("200000"), 1)))).total()).isEqualTo(brl("600.00"));
        assertThat(CommissionEngine.calculate(new EngineInput(rules, List.of(), BigDecimal.ZERO, 22, null, null,
                List.of(new PoolShare(TEAM, brl("200000"), 4)))).total()).isEqualTo(brl("150.00"));
    }

    @Test
    void notEligibleBelowMinimumWorkedDays() {
        PlanRules r = base();
        PlanRules rules = new PlanRules(new Eligibility(Set.of(), Set.of(), Set.of(), Set.of(), 10), r.recoveryRate(), null,
                null, List.of(), r.quality(), r.attribution(), null, r.limits(), r.clearing());
        CalculationResult result = CommissionEngine.calculate(new EngineInput(rules,
                List.of(volume(DelayBucket.D0_30, "120000")), BigDecimal.ZERO, 3, null, null, null));
        assertThat(result.total()).isEqualTo(Money.ZERO);
        assertThat(result.outcome()).isEqualTo(StatementOutcome.NOT_ELIGIBLE);
    }

    @Test
    void periodCapLimitsTheTotal() {
        PlanRules r = base();
        PlanRules rules = new PlanRules(r.eligibility(), r.recoveryRate(), null, null, List.of(), r.quality(),
                r.attribution(), null, new Limits(new BigDecimal("2000")), r.clearing());
        assertThat(run(rules, List.of(volume(DelayBucket.D0_30, "120000"))).total()).isEqualTo(brl("2000.00"));
    }

    @Test
    void standardMarketPlanCombination() {
        // 60.000 (31–90, × 1,0) + 40.000 (> 360, × 1,8) = 100.000 → 1.750 (taxa efetiva 1,75%)
        //   60.000 × 1,75% = 1.050,00; 40.000 × 1,75% × 1,8 = 1.260,00 → 2.310,00
        // meta 80.000 → 125% > 110% → × 1,2: +462,00 → 2.772,00
        // envelope 88% > 85% (12 acordos) → −10%: −277,20 → 2.494,80; quebra 3/12 = 25% ≤ 35%
        // monitoria 8,2 ≥ 7,0 → liberado
        CalculationResult result = CommissionEngine.calculate(new EngineInput(StandardPlans.padraoMercado(),
                List.of(volume(DelayBucket.D31_90, "60000"), volume(DelayBucket.D361_PLUS, "40000")), BigDecimal.ZERO, 20,
                goal("80000", "100000"), new QualityInput(12, 3, 12, new BigDecimal("88"), 4, new BigDecimal("8.2"), 0),
                null));
        assertThat(result.total()).isEqualTo(brl("2494.80"));
        assertThat(result.lines().stream().map(CalculationResult.Line::amount).reduce(Money.ZERO, Money::plus))
                .isEqualTo(result.total());
        assertThat(result.lines()).extracting(CalculationResult.Line::component).containsExactly(Component.RATE,
                Component.RATE, Component.GOAL, Component.DEFLATOR_EARLY_BREAK, Component.DEFLATOR_ENVELOPE,
                Component.QUALITY_GATE);
    }

    @Test
    void forecastRateIsTheFirstTierTimesTheDifficultyMultiplier() {
        assertThat(CommissionEngine.forecastRatePercent(StandardPlans.padraoMercado(), segment(DelayBucket.D361_PLUS)))
                .isEqualByComparingTo("2.70");
    }
}
