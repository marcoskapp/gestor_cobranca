package br.com.gestorcobranca.incentive.domain.model.plan;

/** Métrica da meta (§4.2). */
public enum GoalMetric {
    /** Valor recuperado efetivo creditado (R$). */
    RECOVERED,
    /** Taxa de recuperação (%) sobre o saldo elegível atribuído no início do período. */
    RECOVERY_RATE,
    /** Recuperado ÷ esperado pelo score (depende do módulo scoring; ainda não disponível). */
    RECOVERED_VS_EXPECTED
}
