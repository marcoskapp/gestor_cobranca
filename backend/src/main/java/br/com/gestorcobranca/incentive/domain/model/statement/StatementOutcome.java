package br.com.gestorcobranca.incentive.domain.model.statement;

/** Resultado do cálculo do variável do período. */
public enum StatementOutcome {
    /** Variável calculado normalmente (pode ser zero). */
    PAYABLE,
    /** Não atendeu à elegibilidade do plano (ex.: dias trabalhados mínimos). */
    NOT_ELIGIBLE,
    /** Nenhum plano publicado e vigente cobre o negociador no período. */
    NO_PLAN,
    /** Portão de monitoria: variável suspenso até reavaliação (recalcular após nova monitoria). */
    SUSPENDED_QUALITY_GATE,
    /** Violação grave de compliance confirmada: variável do período zerado (contestável). */
    ZEROED_COMPLIANCE
}
