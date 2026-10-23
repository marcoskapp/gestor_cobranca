package br.com.gestorcobranca.incentive.domain.model.plan;

import br.com.gestorcobranca.incentive.domain.model.DelayBucket;
import br.com.gestorcobranca.incentive.domain.model.attribution.AgreementOrigin;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.AttributionRules;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.ClearingRules;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.DifficultyMultiplier;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.EarlyBreakDeflator;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.Eligibility;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.EnvelopeDeflator;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.GoalComponent;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.Limits;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.MonitoringGate;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.QualityModifiers;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.RateTable;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.RateTier;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.RecoveryRateComponent;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.TeamPoolComponent;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plano de referência semeado em toda assessoria ({@code PADRAO_MERCADO}, §4.3). Os números são
 * referências usuais de mercado e devem ser calibrados pela operação (nova versão do plano).
 */
public final class StandardPlans {

    public static final String PADRAO_MERCADO = "PADRAO_MERCADO";

    private StandardPlans() {
    }

    public static PlanRules padraoMercado() {
        return new PlanRules(
                new Eligibility(Set.of("OPERADOR", "SUPERVISOR"), Set.of(), Set.of(), Set.of(), 0),
                new RecoveryRateComponent(TierMode.PROGRESSIVE, List.of(new RateTable(SegmentSelector.ANY, List.of(
                        new RateTier(BigDecimal.ZERO, new BigDecimal("1.5")),
                        new RateTier(new BigDecimal("50000"), new BigDecimal("2.0")),
                        new RateTier(new BigDecimal("100000"), new BigDecimal("2.5")))))),
                null,
                new GoalComponent(GoalMetric.RECOVERED, GoalScope.INDIVIDUAL, null, new BigDecimal("80"),
                        new BigDecimal("0.5"), new BigDecimal("110"), new BigDecimal("1.2"), AcceleratorMode.TOTAL,
                        new BigDecimal("300")),
                List.of(new DifficultyMultiplier(SegmentSelector.buckets(DelayBucket.D0_30, DelayBucket.D31_90), BigDecimal.ONE),
                        new DifficultyMultiplier(SegmentSelector.buckets(DelayBucket.D91_180), new BigDecimal("1.2")),
                        new DifficultyMultiplier(SegmentSelector.buckets(DelayBucket.D181_360), new BigDecimal("1.4")),
                        new DifficultyMultiplier(SegmentSelector.buckets(DelayBucket.D361_PLUS), new BigDecimal("1.8"))),
                new QualityModifiers(
                        new EarlyBreakDeflator(60, new BigDecimal("35"), new BigDecimal("20"), 10),
                        new EnvelopeDeflator(new BigDecimal("85"), new BigDecimal("10"), 10),
                        new MonitoringGate(new BigDecimal("7.0"), false),
                        true),
                new AttributionRules(Map.of(
                        AgreementOrigin.HUMAN, new BigDecimal("100"),
                        AgreementOrigin.HUMAN_COPILOT, new BigDecimal("100"),
                        AgreementOrigin.ASSISTANT_HANDOFF_NEGOTIATED, new BigDecimal("100"),
                        AgreementOrigin.ASSISTANT_HANDOFF_CONFIRMED, new BigDecimal("30"),
                        AgreementOrigin.ASSISTANT_AUTONOMOUS, BigDecimal.ZERO,
                        AgreementOrigin.PORTAL_SELF_SERVICE, new BigDecimal("50"),
                        AgreementOrigin.CAMPAIGN, new BigDecimal("50")),
                        7, CreditModel.ORIGINATOR, new BigDecimal("50"),
                        TerminationPolicy.CREDIT_UNTIL_TERMINATION_PLUS_GRACE, 5),
                new TeamPoolComponent(new BigDecimal("0.3"), true, PoolDistribution.SUPERVISOR),
                new Limits(null),
                new ClearingRules(5, false));
    }
}
