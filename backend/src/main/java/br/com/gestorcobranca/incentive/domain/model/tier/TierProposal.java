package br.com.gestorcobranca.incentive.domain.model.tier;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Classificação proposta de uma pessoa: quartis, base e nível (explicável).
 *
 * @param qualityQuartile    1–4 já com o portão aplicado; nulo sem avaliações suficientes
 * @param efficiencyQuartile 1–4; nulo sem casos suficientes nas safras
 * @param qualityGateFailed  falha crítica ou nota média abaixo do portão (qualidade limitada a Q2)
 * @param recoveryRatio      realizado ÷ esperado (4 casas); nulo sem valor esperado
 * @param proposedTier       pela matriz; o nível padrão quando falta algum quartil
 */
public record TierProposal(UUID userId, Integer qualityQuartile, Integer efficiencyQuartile, boolean qualityGateFailed,
                           long evaluations, BigDecimal qualityAverage, long cases, BigDecimal recoveryRatio,
                           Tier proposedTier) {

    public boolean missingQuality() {
        return qualityQuartile == null;
    }

    public boolean missingEfficiency() {
        return efficiencyQuartile == null;
    }

    public boolean missingSample() {
        return missingQuality() || missingEfficiency();
    }
}
