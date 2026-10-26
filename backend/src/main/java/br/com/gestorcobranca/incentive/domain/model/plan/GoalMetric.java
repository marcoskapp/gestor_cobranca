package br.com.gestorcobranca.incentive.domain.model.plan;

/** Métrica da meta (§4.2). */
public enum GoalMetric {
    /** Valor recuperado efetivo creditado (R$). */
    RECOVERED(GoalUnit.CURRENCY),
    /** Taxa de recuperação (%) sobre o saldo elegível atribuído no início do período. */
    RECOVERY_RATE(GoalUnit.PERCENT),
    /** Recuperado ÷ esperado pelo score, em % (o motor de comissão ainda não apura meta por ela). */
    RECOVERED_VS_EXPECTED(GoalUnit.PERCENT);

    private final GoalUnit unit;

    GoalMetric(GoalUnit unit) {
        this.unit = unit;
    }

    /** Única unidade em que a meta desta métrica é expressa. */
    public GoalUnit unit() {
        return unit;
    }
}
