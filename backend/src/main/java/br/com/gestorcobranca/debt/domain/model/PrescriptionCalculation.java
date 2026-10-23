package br.com.gestorcobranca.debt.domain.model;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Resultado do cálculo da prescrição de uma pretensão de um título.
 *
 * @param baseDate               data-base conforme a regra (vencimento do título ou do contrato)
 * @param countingStart          início da contagem vigente: data-base deslocada ou última interrupção eficaz
 * @param ruleId                 regra aplicada
 * @param termMonths             prazo aplicado
 * @param estimatedDate          data estimada da prescrição
 * @param effectiveInterruptions interrupções que reiniciaram a contagem
 */
public record PrescriptionCalculation(PrescriptionClaim claim, LocalDate baseDate, LocalDate countingStart, UUID ruleId,
                                      int termMonths, LocalDate estimatedDate, Set<UUID> effectiveInterruptions) {

    public PrescriptionCalculation {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(baseDate, "baseDate");
        Objects.requireNonNull(countingStart, "countingStart");
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(estimatedDate, "estimatedDate");
        effectiveInterruptions = Set.copyOf(effectiveInterruptions);
    }
}
