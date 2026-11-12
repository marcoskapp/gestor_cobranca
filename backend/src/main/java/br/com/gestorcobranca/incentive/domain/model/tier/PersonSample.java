package br.com.gestorcobranca.incentive.domain.model.tier;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Insumos de uma pessoa na janela de apuração.
 *
 * @param evaluations     avaliações de monitoria
 * @param qualityAverage  nota média da monitoria (0–10); nula sem avaliação
 * @param criticalFailure alguma avaliação com falha crítica
 * @param cases           casos nas safras de score encerradas (recuperado × esperado)
 * @param expectedAmount  Σ valor esperado das safras
 * @param realizedAmount  Σ valor realizado das safras
 */
public record PersonSample(UUID userId, long evaluations, BigDecimal qualityAverage, boolean criticalFailure,
                           long cases, BigDecimal expectedAmount, BigDecimal realizedAmount) {

    public static PersonSample empty(UUID userId) {
        return new PersonSample(userId, 0, null, false, 0, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
