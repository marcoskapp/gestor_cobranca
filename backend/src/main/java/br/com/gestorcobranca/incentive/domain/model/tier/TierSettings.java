package br.com.gestorcobranca.incentive.domain.model.tier;

import br.com.gestorcobranca.shared.domain.Guard;

import java.math.BigDecimal;

/**
 * Parâmetros da classificação da assessoria (distribuicao-por-desempenho.md §11.2). Sem linha gravada valem os
 * padrões ({@link #defaults()}).
 *
 * @param windowDays         janela de apuração em dias corridos até ontem (30–365)
 * @param minEvaluations     avaliações de monitoria na janela para entrar no ranking de qualidade (1–100)
 * @param minCases           casos nas safras de score da janela para entrar no ranking de eficiência (1–10.000)
 * @param qualityGate        portão de qualidade (0–10, até 2 casas): nota média abaixo dele → qualidade no máximo Q2
 * @param matrix             matriz qualidade × eficiência
 * @param defaultTier        nível de quem não tem amostra suficiente
 * @param periodicity        periodicidade da rodada automática
 * @param reviewBusinessDays prazo de revisão em dias úteis (1–20); sem publicação no prazo, a rodada expira
 */
public record TierSettings(int windowDays, int minEvaluations, int minCases, BigDecimal qualityGate,
                           TierMatrix matrix, Tier defaultTier, TierPeriodicity periodicity, int reviewBusinessDays) {

    public static final int MIN_WINDOW = 30;
    public static final int MAX_WINDOW = 365;

    public TierSettings {
        Guard.require(windowDays >= MIN_WINDOW && windowDays <= MAX_WINDOW, "tier_settings.window_invalid",
                "A janela de apuração vai de 30 a 365 dias");
        Guard.require(minEvaluations >= 1 && minEvaluations <= 100, "tier_settings.min_evaluations_invalid",
                "O mínimo de avaliações vai de 1 a 100");
        Guard.require(minCases >= 1 && minCases <= 10_000, "tier_settings.min_cases_invalid",
                "O mínimo de casos vai de 1 a 10.000");
        Guard.require(qualityGate != null && qualityGate.signum() >= 0 && qualityGate.compareTo(BigDecimal.TEN) <= 0
                        && qualityGate.stripTrailingZeros().scale() <= 2, "tier_settings.quality_gate_invalid",
                "O portão de qualidade vai de 0 a 10, com até 2 casas");
        Guard.require(matrix != null, "tier_settings.matrix_invalid", "Informe a matriz de níveis");
        Guard.require(defaultTier != null, "tier_settings.default_tier_invalid", "Informe o nível padrão");
        Guard.require(periodicity != null, "tier_settings.periodicity_invalid", "Informe a periodicidade");
        Guard.require(reviewBusinessDays >= 1 && reviewBusinessDays <= 20, "tier_settings.review_days_invalid",
                "O prazo de revisão vai de 1 a 20 dias úteis");
    }

    public static TierSettings defaults() {
        return new TierSettings(90, 5, 30, new BigDecimal("7.00"), TierMatrix.standard(), Tier.B,
                TierPeriodicity.MONTHLY, 3);
    }
}
