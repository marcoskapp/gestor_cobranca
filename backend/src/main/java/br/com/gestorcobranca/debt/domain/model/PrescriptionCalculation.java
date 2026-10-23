package br.com.gestorcobranca.debt.domain.model;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Resultado do cálculo da prescrição de um título.
 *
 * @param baseDate               data-base conforme a regra (vencimento do título sem regra)
 * @param countingStart          início da contagem vigente: data-base ou última interrupção eficaz
 * @param ruleId                 regra aplicada; nulo sem regra para a natureza do título
 * @param termYears              prazo aplicado; nulo sem regra
 * @param estimatedDate          data estimada da prescrição; nula sem regra
 * @param effectiveInterruptions interrupções que reiniciaram a contagem
 */
public record PrescriptionCalculation(LocalDate baseDate, LocalDate countingStart, UUID ruleId, Integer termYears,
                                      LocalDate estimatedDate, Set<UUID> effectiveInterruptions) {

    public PrescriptionCalculation {
        Objects.requireNonNull(baseDate, "baseDate");
        Objects.requireNonNull(countingStart, "countingStart");
        effectiveInterruptions = Set.copyOf(effectiveInterruptions);
    }

    public boolean hasRule() {
        return ruleId != null;
    }
}
