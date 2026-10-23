package br.com.gestorcobranca.incentive.domain.service;

import br.com.gestorcobranca.incentive.domain.model.statement.StatementOutcome;
import br.com.gestorcobranca.shared.domain.Money;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resultado do motor: valor calculado e memória de cálculo em linhas. A soma de {@code amount}
 * das linhas é igual a {@link #total()}.
 *
 * @param withheld           valor suspenso pelo portão de monitoria (liberável por recálculo)
 * @param creditedRecovery   recuperado creditado considerado (soma dos volumes)
 * @param attainmentPercent  atingimento da meta (null sem meta)
 * @param goalFactor         fator efetivo da meta (1 sem meta)
 * @param deflatorPercent    redução total por deflatores de qualidade
 */
public record CalculationResult(StatementOutcome outcome, Money total, Money withheld, Money creditedRecovery,
                                BigDecimal attainmentPercent, BigDecimal goalFactor, BigDecimal deflatorPercent,
                                List<Line> lines) {

    public CalculationResult {
        lines = List.copyOf(lines);
    }

    public enum Component {
        ELIGIBILITY, COVERAGE, GOAL_CAP, RATE, FIXED, GOAL, DEFLATOR_EARLY_BREAK, DEFLATOR_ENVELOPE, POOL,
        COMPLIANCE_ZERO, QUALITY_GATE, FLOOR, CAP
    }

    /**
     * @param base   base do componente (volume, valor do pool…), quando houver
     * @param rate   taxa aplicada em % (ou atingimento, no componente de meta)
     * @param factor fator aplicado (multiplicador de dificuldade, fator da meta, divisor do pool)
     * @param amount efeito da linha no total (positivo ou negativo)
     */
    public record Line(int number, Component component, String description, String segmentKey, Money base,
                       BigDecimal rate, BigDecimal factor, Money amount) {
    }
}
