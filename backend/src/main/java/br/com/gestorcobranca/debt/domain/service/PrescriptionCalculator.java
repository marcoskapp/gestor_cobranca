package br.com.gestorcobranca.debt.domain.service;

import br.com.gestorcobranca.debt.domain.model.InterruptionPolicy;
import br.com.gestorcobranca.debt.domain.model.PrescriptionBase;
import br.com.gestorcobranca.debt.domain.model.PrescriptionCalculation;
import br.com.gestorcobranca.debt.domain.model.PrescriptionRule;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Cálculo da prescrição estimada de uma pretensão de um título a partir da regra do tenant.
 * <p>
 * Contagem:
 * <ol>
 *   <li>data-base: vencimento do título ou, com {@link PrescriptionBase#CONTRACT_FINAL_DUE_DATE},
 *       o maior vencimento do contrato (se posterior);</li>
 *   <li>início da contagem: data-base mais o deslocamento da regra (ex.: prazo de apresentação
 *       do cheque);</li>
 *   <li>data estimada: início mais o prazo em meses ({@link LocalDate#plusMonths}: fim de mês
 *       ajustado ao último dia válido).</li>
 * </ol>
 * As interrupções são consideradas em ordem cronológica; uma interrupção é eficaz se ocorrer a
 * partir do início da contagem vigente e até a data estimada vigente (não ressuscita prazo já
 * consumado nem interrompe prazo que ainda não começou a correr). Cada interrupção eficaz
 * reinicia a contagem pelo prazo inteiro a partir da sua data; com
 * {@link InterruptionPolicy#FIRST_ONLY} apenas a primeira produz efeito (CC art. 202, caput).
 */
public final class PrescriptionCalculator {

    private PrescriptionCalculator() {
    }

    /** Fato de interrupção considerado no cálculo. */
    public record InterruptionFact(UUID id, LocalDate date) {

        public InterruptionFact {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(date, "date");
        }
    }

    /**
     * @param titleDueDate         vencimento do título
     * @param contractFinalDueDate maior vencimento entre os títulos do contrato (nulo sem contrato)
     * @param rule                 regra da natureza do título e da pretensão
     */
    public static PrescriptionCalculation calculate(LocalDate titleDueDate, LocalDate contractFinalDueDate,
                                                    PrescriptionRule rule, Collection<InterruptionFact> interruptions) {
        Objects.requireNonNull(titleDueDate, "titleDueDate");
        Objects.requireNonNull(rule, "rule");
        LocalDate base = rule.getBase() == PrescriptionBase.CONTRACT_FINAL_DUE_DATE && contractFinalDueDate != null
                && contractFinalDueDate.isAfter(titleDueDate) ? contractFinalDueDate : titleDueDate;
        LocalDate start = base.plusDays(rule.getStartOffsetDays());
        LocalDate estimated = start.plusMonths(rule.getTermMonths());
        Set<UUID> effective = new LinkedHashSet<>();
        List<InterruptionFact> ordered = interruptions.stream()
                .sorted(Comparator.comparing(InterruptionFact::date).thenComparing(InterruptionFact::id)).toList();
        for (InterruptionFact fact : ordered) {
            if (fact.date().isBefore(start) || fact.date().isAfter(estimated)) {
                continue;
            }
            effective.add(fact.id());
            start = fact.date();
            estimated = fact.date().plusMonths(rule.getTermMonths());
            if (rule.getInterruptionPolicy() == InterruptionPolicy.FIRST_ONLY) {
                break;
            }
        }
        return new PrescriptionCalculation(rule.getClaim(), base, start, rule.getId(), rule.getTermMonths(), estimated,
                effective);
    }
}
