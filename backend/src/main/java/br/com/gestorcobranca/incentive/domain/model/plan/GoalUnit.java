package br.com.gestorcobranca.incentive.domain.model.plan;

/**
 * Unidade da meta: valor em reais (métrica "valor recuperado") ou percentual (métrica "taxa de
 * recuperação"). A unidade é escolhida explicitamente ao cadastrar a meta do período e precisa
 * coincidir com a da métrica do plano que a usa; o motor nunca compara R$ com %.
 */
public enum GoalUnit {
    /** Reais, com no máximo 2 casas decimais (centavos). */
    CURRENCY("R$"),
    /** Percentual de 0,01 a 100, com no máximo 2 casas decimais. */
    PERCENT("%");

    private final String symbol;

    GoalUnit(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }
}
