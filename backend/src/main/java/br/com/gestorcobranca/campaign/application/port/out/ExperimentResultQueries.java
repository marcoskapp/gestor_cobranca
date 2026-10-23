package br.com.gestorcobranca.campaign.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Totais por braço de um teste A/B, por devedor, dentro do horizonte de observação. */
public interface ExperimentResultQueries {

    /**
     * @param matureBefore só devedores inscritos até este instante (nulo = todos)
     * @param observationDays resultados até {@code inscrição + horizonte}
     */
    List<ArmTotals> totals(UUID experimentId, Instant matureBefore, int observationDays);

    /**
     * @param debtors          devedores distintos do braço (denominador)
     * @param recovered        soma do valor recuperado líquido por devedor
     * @param recoveredSquares soma dos quadrados do valor recuperado líquido por devedor
     */
    record ArmTotals(String arm, boolean control, long debtors, long effectiveContact, long replied, long agreement,
                     long paying, BigDecimal recovered, BigDecimal recoveredSquares) {
    }
}
