package br.com.gestorcobranca.incentive.domain.service;

import br.com.gestorcobranca.incentive.domain.model.Segment;
import br.com.gestorcobranca.incentive.domain.model.plan.AcceleratorMode;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.EarlyBreakDeflator;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.EnvelopeDeflator;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.GoalComponent;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.MonitoringGate;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.RateTable;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.RateTier;
import br.com.gestorcobranca.incentive.domain.model.plan.TierMode;
import br.com.gestorcobranca.incentive.domain.model.statement.StatementOutcome;
import br.com.gestorcobranca.incentive.domain.service.CalculationResult.Component;
import br.com.gestorcobranca.incentive.domain.service.CalculationResult.Line;
import br.com.gestorcobranca.incentive.domain.service.EngineInput.GoalInput;
import br.com.gestorcobranca.incentive.domain.service.EngineInput.PoolShare;
import br.com.gestorcobranca.incentive.domain.service.EngineInput.QualityInput;
import br.com.gestorcobranca.incentive.domain.service.EngineInput.SegmentVolume;
import br.com.gestorcobranca.shared.domain.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Motor de cálculo do variável do período (§4.2), puro e determinístico. Ordem de aplicação,
 * registrada linha a linha na memória de cálculo:
 * <ol>
 *   <li><b>Elegibilidade</b>: dias trabalhados abaixo do mínimo → zero ({@code NOT_ELIGIBLE}).</li>
 *   <li><b>Cobertura</b>: créditos de credores/carteiras fora do plano não remuneram.</li>
 *   <li><b>Teto da meta</b>: atingimento acima do teto escala o volume por {@code teto ÷ atingimento}.</li>
 *   <li><b>Percentual sobre recuperado</b>: por tabela (seleção de segmento mais específica), faixas
 *       {@code PROGRESSIVE} (cada faixa à sua taxa) ou {@code REACHED_TIER} (taxa da faixa atingida
 *       sobre todo o volume). A comissão da tabela é distribuída aos segmentos proporcionalmente ao
 *       volume e multiplicada pelo multiplicador de dificuldade do segmento — o multiplicador não
 *       antecipa a progressão das faixas.</li>
 *   <li><b>Fixo por acordo</b> com 1ª parcela paga × fração de crédito.</li>
 *   <li><b>Meta</b>: abaixo do gatilho paga o fator reduzido; acima do limiar aplica o acelerador
 *       ({@code TOTAL}: sobre todo o variável; {@code EXCESS}: só sobre a parcela proporcional ao
 *       que excede o limiar).</li>
 *   <li><b>Deflatores</b> de quebra precoce e de uso do envelope, somados (máximo 100%).</li>
 *   <li><b>Pool</b> de equipe (taxa × recuperado da equipe ÷ divisor) — fora de meta e deflatores.</li>
 *   <li><b>Zerador</b> por violação grave de compliance, depois o <b>portão de monitoria</b>
 *       (suspende tudo, inclusive o pool).</li>
 *   <li><b>Piso</b> zero (compensações não geram valor negativo) e <b>teto</b> por período.</li>
 * </ol>
 * Arredondamento: centavos HALF_EVEN em cada linha; o total é a soma das linhas.
 */
public final class CommissionEngine {

    private static final int SCALE = 10;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private CommissionEngine() {
    }

    public static CalculationResult calculate(EngineInput in) {
        PlanRules rules = in.rules();
        Memory memory = new Memory();
        Money credited = in.volumes().stream().map(SegmentVolume::amount).reduce(Money.ZERO, Money::plus);

        if (in.workedDays() < rules.eligibility().minWorkedDays()) {
            memory.add(Component.ELIGIBILITY, "Dias trabalhados %d abaixo do mínimo %d do plano"
                    .formatted(in.workedDays(), rules.eligibility().minWorkedDays()), null, null, null, null, Money.ZERO);
            return memory.result(StatementOutcome.NOT_ELIGIBLE, Money.ZERO, credited, null, BigDecimal.ONE, BigDecimal.ZERO);
        }

        List<SegmentVolume> covered = new ArrayList<>();
        for (SegmentVolume v : sorted(in.volumes())) {
            if (rules.eligibility().coversSegment(v.segment())) {
                covered.add(v);
            } else {
                memory.add(Component.COVERAGE, "Segmento fora da cobertura do plano", v.segment().key(), v.amount(), null,
                        null, Money.ZERO);
            }
        }

        GoalEvaluation goal = evaluateGoal(rules.goal(), in.goal());
        BigDecimal capScale = BigDecimal.ONE;
        if (goal.attainment() != null && rules.goal().capPercent() != null
                && goal.attainment().compareTo(rules.goal().capPercent()) > 0) {
            capScale = rules.goal().capPercent().divide(goal.attainment(), SCALE, RoundingMode.HALF_EVEN);
            memory.add(Component.GOAL_CAP, "Atingimento %s%% acima do teto %s%%: volume remunerado escalado"
                            .formatted(plain(goal.attainment()), plain(rules.goal().capPercent())), null, null,
                    goal.attainment(), capScale, Money.ZERO);
        }

        Money subtotal = Money.ZERO;
        if (rules.recoveryRate() != null) {
            subtotal = subtotal.plus(rateComponent(rules, covered, capScale, memory));
        }
        if (rules.fixedPerAgreement() != null) {
            Money fixed = Money.of(rules.fixedPerAgreement().amount().multiply(in.firstInstallmentWeight()));
            memory.add(Component.FIXED, "Valor fixo por acordo com 1ª parcela paga", null, Money.of(rules.fixedPerAgreement().amount()),
                    null, in.firstInstallmentWeight(), fixed);
            subtotal = subtotal.plus(fixed);
        }

        Money running = subtotal;
        BigDecimal goalFactor = BigDecimal.ONE;
        if (rules.goal() != null) {
            if (goal.attainment() == null) {
                memory.add(Component.GOAL, "Sem meta definida para o período: fator 1", null, null, null, BigDecimal.ONE, Money.ZERO);
            } else {
                GoalComponent g = rules.goal();
                Money delta = Money.ZERO;
                String description;
                if (goal.attainment().compareTo(g.triggerPercent()) < 0) {
                    goalFactor = g.belowTriggerFactor();
                    delta = subtotal.times(goalFactor.subtract(BigDecimal.ONE));
                    description = "Atingimento abaixo do gatilho de %s%%".formatted(plain(g.triggerPercent()));
                } else if (goal.attainment().compareTo(g.acceleratorThresholdPercent()) > 0) {
                    goalFactor = g.acceleratorFactor();
                    BigDecimal share = BigDecimal.ONE;
                    if (g.acceleratorMode() == AcceleratorMode.EXCESS) {
                        share = goal.attainment().subtract(g.acceleratorThresholdPercent())
                                .divide(goal.attainment(), SCALE, RoundingMode.HALF_EVEN);
                    }
                    delta = subtotal.times(share.multiply(goalFactor.subtract(BigDecimal.ONE)));
                    description = "Acelerador acima de %s%% (%s)".formatted(plain(g.acceleratorThresholdPercent()),
                            g.acceleratorMode() == AcceleratorMode.EXCESS ? "sobre o excedente" : "sobre o total");
                } else {
                    description = "Meta entre o gatilho e o limiar do acelerador: fator 1";
                }
                memory.add(Component.GOAL, description, null, subtotal, goal.attainment(), goalFactor, delta);
                running = running.plus(delta);
            }
        }

        BigDecimal deflator = BigDecimal.ZERO;
        QualityInput q = in.quality();
        EarlyBreakDeflator earlyBreak = rules.quality().earlyBreak();
        if (earlyBreak != null) {
            BigDecimal applied = BigDecimal.ZERO;
            BigDecimal rate = null;
            String description;
            if (q.safraAgreements() < earlyBreak.minSample()) {
                description = "Quebra precoce %dd: safra de %d acordo(s) abaixo da amostra mínima %d"
                        .formatted(earlyBreak.windowDays(), q.safraAgreements(), earlyBreak.minSample());
            } else {
                rate = BigDecimal.valueOf(q.safraEarlyBreaks()).multiply(HUNDRED)
                        .divide(BigDecimal.valueOf(q.safraAgreements()), 4, RoundingMode.HALF_EVEN);
                if (rate.compareTo(earlyBreak.thresholdPercent()) > 0) {
                    applied = earlyBreak.deflatorPercent();
                    description = "Quebra precoce %dd de %s%% acima do limite de %s%%"
                            .formatted(earlyBreak.windowDays(), plain(rate), plain(earlyBreak.thresholdPercent()));
                } else {
                    description = "Quebra precoce %dd de %s%% dentro do limite de %s%%"
                            .formatted(earlyBreak.windowDays(), plain(rate), plain(earlyBreak.thresholdPercent()));
                }
            }
            Money amount = running.times(applied.divide(HUNDRED, SCALE, RoundingMode.HALF_EVEN)).negate();
            memory.add(Component.DEFLATOR_EARLY_BREAK, description, null, running, rate, applied, amount);
            deflator = deflator.add(applied);
        }
        EnvelopeDeflator envelope = rules.quality().envelope();
        if (envelope != null) {
            BigDecimal applied = BigDecimal.ZERO;
            String description;
            if (q.envelopeSample() < envelope.minSample() || q.envelopeUsageAverage() == null) {
                description = "Uso do envelope: %d acordo(s) abaixo da amostra mínima %d"
                        .formatted(q.envelopeSample(), envelope.minSample());
            } else if (q.envelopeUsageAverage().compareTo(envelope.thresholdPercent()) > 0) {
                applied = envelope.deflatorPercent().min(HUNDRED.subtract(deflator));
                description = "Uso médio do envelope de %s%% acima do limite de %s%%"
                        .formatted(plain(q.envelopeUsageAverage()), plain(envelope.thresholdPercent()));
            } else {
                description = "Uso médio do envelope de %s%% dentro do limite de %s%%"
                        .formatted(plain(q.envelopeUsageAverage()), plain(envelope.thresholdPercent()));
            }
            Money base = running;
            Money amount = base.times(applied.divide(HUNDRED, SCALE, RoundingMode.HALF_EVEN)).negate();
            memory.add(Component.DEFLATOR_ENVELOPE, description, null, base, q.envelopeUsageAverage(), applied, amount);
            deflator = deflator.add(applied);
        }
        running = running.plus(memory.sum(Component.DEFLATOR_EARLY_BREAK)).plus(memory.sum(Component.DEFLATOR_ENVELOPE));

        if (rules.teamPool() != null) {
            for (PoolShare share : in.poolShares()) {
                Money base = share.teamRecovered().isNegative() ? Money.ZERO : share.teamRecovered();
                BigDecimal fraction = rules.teamPool().ratePercent().divide(HUNDRED, SCALE, RoundingMode.HALF_EVEN)
                        .divide(BigDecimal.valueOf(Math.max(1, share.divisor())), SCALE, RoundingMode.HALF_EVEN);
                Money amount = base.times(fraction);
                memory.add(Component.POOL, share.divisor() > 1
                                ? "Pool da equipe dividido entre %d pessoas".formatted(share.divisor())
                                : "Pool da equipe (supervisor)", share.teamId().toString(), base,
                        rules.teamPool().ratePercent(), BigDecimal.valueOf(share.divisor()), amount);
                running = running.plus(amount);
            }
        }

        StatementOutcome outcome = StatementOutcome.PAYABLE;
        Money withheld = Money.ZERO;
        if (rules.quality().zeroOnSevereViolation() && q.severeViolations() > 0) {
            memory.add(Component.COMPLIANCE_ZERO, "Violação grave de compliance confirmada (%d): variável zerado"
                    .formatted(q.severeViolations()), null, running, null, BigDecimal.ZERO, running.negate());
            running = Money.ZERO;
            outcome = StatementOutcome.ZEROED_COMPLIANCE;
        }
        MonitoringGate gate = rules.quality().monitoringGate();
        if (gate != null && outcome == StatementOutcome.PAYABLE) {
            boolean suspend;
            String description;
            if (q.evaluations() == 0 || q.qaAverage() == null) {
                suspend = gate.suspendWithoutEvaluation();
                description = suspend ? "Sem monitoria no período: variável suspenso até avaliação"
                        : "Sem monitoria no período: portão não aplicado";
            } else {
                suspend = q.qaAverage().compareTo(gate.minScore()) < 0;
                description = "Nota média de monitoria %s %s mínimo %s".formatted(plain(q.qaAverage()),
                        suspend ? "abaixo do" : "atende ao", plain(gate.minScore()));
            }
            if (suspend && running.isPositive()) {
                withheld = running;
                memory.add(Component.QUALITY_GATE, description + " (suspenso até reavaliação)", null, running, q.qaAverage(),
                        null, running.negate());
                running = Money.ZERO;
                outcome = StatementOutcome.SUSPENDED_QUALITY_GATE;
            } else {
                memory.add(Component.QUALITY_GATE, description, null, running, q.qaAverage(), null, Money.ZERO);
                if (suspend) {
                    outcome = StatementOutcome.SUSPENDED_QUALITY_GATE;
                }
            }
        }
        if (running.isNegative()) {
            memory.add(Component.FLOOR, "Compensações excedem os créditos: piso zero (sem valor negativo)", null, running,
                    null, null, running.negate());
            running = Money.ZERO;
        }
        if (rules.limits().periodCap() != null) {
            Money cap = Money.of(rules.limits().periodCap());
            if (running.isGreaterThan(cap)) {
                memory.add(Component.CAP, "Teto do período", null, running, null, null, cap.minus(running));
                running = cap;
            }
        }
        Money total = memory.total();
        if (!total.equals(running)) {
            throw new IllegalStateException("Memória de cálculo inconsistente: " + total + " ≠ " + running);
        }
        return memory.result(outcome, withheld, credited, goal.attainment(), goalFactor, deflator);
    }

    /** Taxa de referência para a comissão prevista (§5.2): 1ª faixa da tabela do segmento × multiplicador. */
    public static BigDecimal forecastRatePercent(PlanRules rules, Segment segment) {
        if (rules.recoveryRate() == null || !rules.eligibility().coversSegment(segment)) {
            return BigDecimal.ZERO;
        }
        return rules.recoveryRate().tableFor(segment)
                .map(t -> t.tiers().getFirst().ratePercent().multiply(rules.multiplierFor(segment)))
                .orElse(BigDecimal.ZERO);
    }

    private static Money rateComponent(PlanRules rules, List<SegmentVolume> covered, BigDecimal capScale, Memory memory) {
        Map<RateTable, List<SegmentVolume>> byTable = new LinkedHashMap<>();
        for (SegmentVolume v : covered) {
            Optional<RateTable> table = rules.recoveryRate().tableFor(v.segment());
            if (table.isEmpty()) {
                memory.add(Component.RATE, "Segmento sem tabela de taxas no plano", v.segment().key(), v.amount(), null, null,
                        Money.ZERO);
            } else {
                byTable.computeIfAbsent(table.get(), t -> new ArrayList<>()).add(v);
            }
        }
        Money total = Money.ZERO;
        for (Map.Entry<RateTable, List<SegmentVolume>> group : byTable.entrySet()) {
            BigDecimal volume = BigDecimal.ZERO;
            for (SegmentVolume v : group.getValue()) {
                volume = volume.add(v.amount().amount().multiply(capScale));
            }
            if (volume.signum() <= 0) {
                for (SegmentVolume v : group.getValue()) {
                    memory.add(Component.RATE, "Volume líquido da tabela não positivo", v.segment().key(),
                            Money.of(v.amount().amount().multiply(capScale)), BigDecimal.ZERO,
                            rules.multiplierFor(v.segment()), Money.ZERO);
                }
                continue;
            }
            BigDecimal commission = tiered(group.getKey(), rules.recoveryRate().mode(), volume);
            BigDecimal effectiveRate = commission.divide(volume, SCALE, RoundingMode.HALF_EVEN);
            for (SegmentVolume v : group.getValue()) {
                BigDecimal segmentVolume = v.amount().amount().multiply(capScale);
                BigDecimal multiplier = rules.multiplierFor(v.segment());
                Money amount = Money.of(segmentVolume.multiply(effectiveRate).multiply(multiplier));
                memory.add(Component.RATE, rules.recoveryRate().mode() == TierMode.PROGRESSIVE
                                ? "Percentual escalonado progressivo sobre recuperado"
                                : "Percentual da faixa atingida sobre recuperado", v.segment().key(),
                        Money.of(segmentVolume), effectiveRate.multiply(HUNDRED).setScale(6, RoundingMode.HALF_EVEN),
                        multiplier, amount);
                total = total.plus(amount);
            }
        }
        return total;
    }

    /** Comissão exata (sem arredondar) de uma tabela de faixas sobre o volume. */
    static BigDecimal tiered(RateTable table, TierMode mode, BigDecimal volume) {
        List<RateTier> tiers = table.tiers();
        if (mode == TierMode.REACHED_TIER) {
            RateTier reached = tiers.getFirst();
            for (RateTier tier : tiers) {
                if (tier == tiers.getFirst() || volume.compareTo(tier.fromAmount()) > 0) {
                    reached = tier;
                }
            }
            return volume.multiply(reached.ratePercent()).divide(HUNDRED, SCALE, RoundingMode.HALF_EVEN);
        }
        BigDecimal commission = BigDecimal.ZERO;
        for (int i = 0; i < tiers.size(); i++) {
            BigDecimal from = tiers.get(i).fromAmount();
            BigDecimal upper = i + 1 < tiers.size() ? tiers.get(i + 1).fromAmount() : null;
            BigDecimal top = upper == null ? volume : volume.min(upper);
            BigDecimal slice = top.subtract(from);
            if (slice.signum() > 0) {
                commission = commission.add(slice.multiply(tiers.get(i).ratePercent()).divide(HUNDRED, SCALE,
                        RoundingMode.HALF_EVEN));
            }
        }
        return commission;
    }

    private record GoalEvaluation(BigDecimal attainment) {
    }

    private static GoalEvaluation evaluateGoal(GoalComponent goal, GoalInput input) {
        if (goal == null || input == null || input.target() == null || input.target().signum() <= 0
                || input.actual() == null) {
            return new GoalEvaluation(null);
        }
        BigDecimal actual = input.actual().signum() < 0 ? BigDecimal.ZERO : input.actual();
        return new GoalEvaluation(actual.multiply(HUNDRED).divide(input.target(), 4, RoundingMode.HALF_EVEN));
    }

    private static List<SegmentVolume> sorted(List<SegmentVolume> volumes) {
        return volumes.stream().sorted(Comparator.comparing(SegmentVolume::segment)).toList();
    }

    private static String plain(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    /** Acumulador das linhas da memória de cálculo. */
    private static final class Memory {

        private final List<Line> lines = new ArrayList<>();

        void add(Component component, String description, String segmentKey, Money base, BigDecimal rate,
                 BigDecimal factor, Money amount) {
            lines.add(new Line(lines.size() + 1, component, description, segmentKey, base, rate, factor, amount));
        }

        Money sum(Component component) {
            return lines.stream().filter(l -> l.component() == component).map(Line::amount).reduce(Money.ZERO, Money::plus);
        }

        Money total() {
            return lines.stream().map(Line::amount).reduce(Money.ZERO, Money::plus);
        }

        CalculationResult result(StatementOutcome outcome, Money withheld, Money credited, BigDecimal attainment,
                                 BigDecimal goalFactor, BigDecimal deflator) {
            return new CalculationResult(outcome, total(), withheld, credited, attainment, goalFactor, deflator, lines);
        }
    }
}
