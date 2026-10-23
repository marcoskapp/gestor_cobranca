package br.com.gestorcobranca.incentive.domain.model;

import br.com.gestorcobranca.incentive.domain.model.attribution.AgreementOrigin;
import br.com.gestorcobranca.incentive.domain.model.plan.CommissionPlan;
import br.com.gestorcobranca.incentive.domain.model.plan.GoalMetric;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.AttributionRules;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.GoalComponent;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.RateTable;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.RateTier;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.RecoveryRateComponent;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanStatus;
import br.com.gestorcobranca.incentive.domain.model.plan.SegmentSelector;
import br.com.gestorcobranca.incentive.domain.model.plan.StandardPlans;
import br.com.gestorcobranca.incentive.domain.model.plan.TierMode;
import br.com.gestorcobranca.incentive.domain.model.quality.QualityCriterion;
import br.com.gestorcobranca.incentive.domain.model.quality.QualityForm;
import br.com.gestorcobranca.incentive.domain.model.statement.CommissionStatement;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementAdjustment;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementOutcome;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementStatus;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Regras de plano (validação tipada e versionamento), extrato (ciclo e segregação) e monitoria. */
class PlanAndStatementTest {

    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    private static PlanRules with(RecoveryRateComponent rate, GoalComponent goal, AttributionRules attribution) {
        PlanRules r = StandardPlans.padraoMercado();
        return new PlanRules(r.eligibility(), rate, r.fixedPerAgreement(), goal, r.difficultyMultipliers(), r.quality(),
                attribution, r.teamPool(), r.limits(), r.clearing());
    }

    @Test
    void standardPlanIsValid() {
        assertThat(StandardPlans.padraoMercado().validate()).isNotNull();
    }

    @Test
    void invalidRulesAreRejectedWithStableCodes() {
        PlanRules r = StandardPlans.padraoMercado();
        assertThatThrownBy(() -> with(new RecoveryRateComponent(TierMode.PROGRESSIVE, List.of(new RateTable(
                SegmentSelector.ANY, List.of(new RateTier(new BigDecimal("100"), BigDecimal.ONE))))), r.goal(),
                r.attribution()).validate()).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "plan.rules.first_tier_invalid");
        assertThatThrownBy(() -> with(new RecoveryRateComponent(TierMode.PROGRESSIVE, List.of(new RateTable(
                SegmentSelector.ANY, List.of(new RateTier(BigDecimal.ZERO, new BigDecimal("101")))))), r.goal(),
                r.attribution()).validate()).hasFieldOrPropertyWithValue("code", "plan.rules.tier_rate_invalid");
        GoalComponent expected = new GoalComponent(GoalMetric.RECOVERED_VS_EXPECTED, r.goal().scope(), null,
                r.goal().triggerPercent(), r.goal().belowTriggerFactor(), r.goal().acceleratorThresholdPercent(),
                r.goal().acceleratorFactor(), r.goal().acceleratorMode(), r.goal().capPercent());
        assertThatThrownBy(() -> with(r.recoveryRate(), expected, r.attribution()).validate())
                .hasFieldOrPropertyWithValue("code", "plan.rules.goal_metric_unavailable");
        Map<AgreementOrigin, BigDecimal> weights = new EnumMap<>(r.attribution().originWeights());
        weights.put(AgreementOrigin.ASSISTANT_AUTONOMOUS, BigDecimal.TEN);
        AttributionRules autonomousPaid = new AttributionRules(weights, 7, r.attribution().creditModel(),
                r.attribution().ownRenegotiationWeightPercent(), r.attribution().terminationPolicy(), 5);
        assertThatThrownBy(() -> with(r.recoveryRate(), r.goal(), autonomousPaid).validate())
                .hasFieldOrPropertyWithValue("code", "plan.rules.autonomous_weight_invalid");
        weights.remove(AgreementOrigin.CAMPAIGN);
        weights.put(AgreementOrigin.ASSISTANT_AUTONOMOUS, BigDecimal.ZERO);
        AttributionRules missing = new AttributionRules(weights, 7, r.attribution().creditModel(),
                r.attribution().ownRenegotiationWeightPercent(), r.attribution().terminationPolicy(), 5);
        assertThatThrownBy(() -> with(r.recoveryRate(), r.goal(), missing).validate())
                .hasFieldOrPropertyWithValue("code", "plan.rules.origin_weight_campaign_required");
    }

    @Test
    void mostSpecificMultiplierWins() {
        PlanRules r = StandardPlans.padraoMercado();
        assertThat(r.multiplierFor(new Segment(UUID.randomUUID(), null, DelayBucket.D181_360))).isEqualByComparingTo("1.4");
        assertThat(r.multiplierFor(new Segment(UUID.randomUUID(), null, DelayBucket.UNKNOWN))).isEqualByComparingTo("1");
    }

    @Test
    void planVersionLifecycle() {
        CommissionPlan v1 = new CommissionPlan("PLANO_A", 1, "Plano A", null, 0, "{}", 1);
        assertThatThrownBy(() -> v1.publish(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 1), UUID.randomUUID(), NOW))
                .hasFieldOrPropertyWithValue("code", "plan.effective_from_invalid");
        assertThatThrownBy(() -> v1.publish(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1), UUID.randomUUID(), NOW))
                .hasFieldOrPropertyWithValue("code", "plan.effective_from_retroactive");
        v1.publish(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1), UUID.randomUUID(), NOW);
        assertThat(v1.isEffectiveOn(LocalDate.of(2026, 10, 1))).isTrue();
        assertThatThrownBy(() -> v1.defineRules("{\"x\":1}", 1)).isInstanceOf(ConflictException.class);
        v1.supersede(LocalDate.of(2026, 12, 1));
        assertThat(v1.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(v1.isEffectiveOn(LocalDate.of(2026, 12, 1))).isFalse();
        assertThatThrownBy(() -> v1.retire(LocalDate.of(2026, 11, 15), LocalDate.of(2026, 10, 31), UUID.randomUUID(), NOW))
                .hasFieldOrPropertyWithValue("code", "plan.effective_to_invalid");
        v1.retire(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 31), UUID.randomUUID(), NOW);
        assertThat(v1.getStatus()).isEqualTo(PlanStatus.RETIRED);
        assertThat(v1.isEffectiveOn(LocalDate.of(2026, 10, 31))).isTrue();
        assertThat(v1.isEffectiveOn(LocalDate.of(2026, 11, 1))).isFalse();
    }

    private static CommissionStatement statement(UUID beneficiary, String amount) {
        return new CommissionStatement(UUID.randomUUID(), LocalDate.of(2026, 9, 1), beneficiary,
                new CommissionStatement.Calculation(CommissionStatement.PlanRef.NONE, StatementOutcome.PAYABLE,
                        Money.of(amount), Money.ZERO, Money.of("1000.00"), List.of(), "{}", 1), null, NOW);
    }

    @Test
    void statementLifecycleAndSegregation() {
        UUID beneficiary = UUID.randomUUID();
        UUID closer = UUID.randomUUID();
        UUID adjuster = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        CommissionStatement s = statement(beneficiary, "100.00");
        s.openDispute();
        assertThat(s.getStatus()).isEqualTo(StatementStatus.DISPUTED);
        assertThatThrownBy(() -> s.approve(approver, closer, Set.of(), 1, 0, NOW)).isInstanceOf(ConflictException.class);
        // contestação resolvida sem ajuste: volta a CALCULATED
        s.reconcile(0, Money.ZERO, false);
        assertThat(s.getStatus()).isEqualTo(StatementStatus.CALCULATED);
        // ajuste negativo maior que o valor: a pagar não fica negativo
        s.reconcile(0, Money.of("-150.00"), true);
        assertThat(s.getStatus()).isEqualTo(StatementStatus.ADJUSTED);
        assertThat(s.getPayableAmount()).isEqualTo(Money.ZERO);
        assertThatThrownBy(() -> s.approve(beneficiary, closer, Set.of(), 0, 0, NOW))
                .hasFieldOrPropertyWithValue("code", "statement.self_approval");
        assertThatThrownBy(() -> s.approve(closer, closer, Set.of(), 0, 0, NOW))
                .hasFieldOrPropertyWithValue("code", "statement.segregation_closer");
        assertThatThrownBy(() -> s.approve(adjuster, closer, Set.of(adjuster), 0, 0, NOW))
                .hasFieldOrPropertyWithValue("code", "statement.segregation_adjuster");
        assertThatThrownBy(() -> s.approve(approver, closer, Set.of(), 0, 1, NOW))
                .hasFieldOrPropertyWithValue("code", "statement.pending_adjustments");
        s.approve(approver, closer, Set.of(adjuster), 0, 0, NOW);
        assertThat(s.getStatus()).isEqualTo(StatementStatus.APPROVED);
        assertThatThrownBy(s::openDispute).isInstanceOf(ConflictException.class);
        s.markExported(UUID.randomUUID(), NOW);
        assertThat(s.getStatus()).isEqualTo(StatementStatus.EXPORTED);
    }

    @Test
    void adjustmentAboveThresholdNeedsASecondApprover() {
        UUID requester = UUID.randomUUID();
        StatementAdjustment small = new StatementAdjustment(UUID.randomUUID(), null, Money.of("-500.00"),
                "Correção de lançamento duplicado", Money.of("500.00"), requester, NOW);
        assertThat(small.isApplied()).isTrue();
        StatementAdjustment large = new StatementAdjustment(UUID.randomUUID(), null, Money.of("500.01"),
                "Acordo omitido no fechamento", Money.of("500.00"), requester, NOW);
        assertThat(large.isPending()).isTrue();
        assertThatThrownBy(() -> large.decide(true, null, requester, NOW))
                .hasFieldOrPropertyWithValue("code", "adjustment.same_approver");
        assertThatThrownBy(() -> large.decide(false, "não", UUID.randomUUID(), NOW))
                .hasFieldOrPropertyWithValue("code", "adjustment.note_required");
        large.decide(true, null, UUID.randomUUID(), NOW);
        assertThat(large.isApplied()).isTrue();
        assertThatThrownBy(() -> new StatementAdjustment(UUID.randomUUID(), null, Money.of("10.00"), "curto",
                Money.of("500.00"), requester, NOW)).hasFieldOrPropertyWithValue("code", "adjustment.reason_invalid");
    }

    @Test
    void qualityScoreIsTheWeightedAverageAndCriticalFailureZeroes() {
        QualityForm form = new QualityForm("Monitoria", null, List.of(
                new QualityCriterion("ABORDAGEM", "Abordagem", new BigDecimal("2"), true),
                new QualityCriterion("ROTEIRO", "Roteiro", new BigDecimal("1"), false)));
        // (8 × 2 + 5 × 1) ÷ 3 = 7,00
        assertThat(form.score(Map.of("ABORDAGEM", new BigDecimal("8"), "ROTEIRO", new BigDecimal("5"))).score())
                .isEqualByComparingTo("7.00");
        // (7 × 2 + 8 × 1) ÷ 3 = 7,333… → 7,33
        assertThat(form.score(Map.of("ABORDAGEM", new BigDecimal("7"), "ROTEIRO", new BigDecimal("8"))).score())
                .isEqualByComparingTo("7.33");
        QualityForm.Score critical = form.score(Map.of("ABORDAGEM", BigDecimal.ZERO, "ROTEIRO", BigDecimal.TEN));
        assertThat(critical.score()).isEqualByComparingTo("0");
        assertThat(critical.criticalFailure()).isTrue();
        assertThatThrownBy(() -> form.score(Map.of("ABORDAGEM", BigDecimal.TEN)))
                .hasFieldOrPropertyWithValue("code", "quality_evaluation.scores_incomplete");
        assertThatThrownBy(() -> form.score(Map.of("ABORDAGEM", new BigDecimal("11"), "ROTEIRO", BigDecimal.ONE)))
                .hasFieldOrPropertyWithValue("code", "quality_evaluation.score_invalid");
    }
}
