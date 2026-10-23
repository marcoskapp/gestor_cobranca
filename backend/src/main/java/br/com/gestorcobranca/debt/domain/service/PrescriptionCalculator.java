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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Cálculo da prescrição estimada de um título a partir da regra do tenant para a natureza do
 * contrato. Sem regra, o título fica sem data estimada (não é erro: o tenant ainda não
 * cadastrou o prazo).
 * <p>
 * Contagem: a partir da data-base, o prazo em anos. As interrupções são consideradas em ordem
 * cronológica; uma interrupção é eficaz se ocorrer a partir do início da contagem vigente e até
 * a data estimada vigente (não ressuscita prazo já consumado). Cada interrupção eficaz reinicia
 * a contagem pelo prazo inteiro a partir da sua data; com {@link InterruptionPolicy#FIRST_ONLY}
 * apenas a primeira produz efeito.
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
     * @param rule                 regra da natureza do título, se cadastrada
     */
    public static PrescriptionCalculation calculate(LocalDate titleDueDate, LocalDate contractFinalDueDate,
                                                    Optional<PrescriptionRule> rule,
                                                    Collection<InterruptionFact> interruptions) {
        Objects.requireNonNull(titleDueDate, "titleDueDate");
        if (rule.isEmpty()) {
            return new PrescriptionCalculation(titleDueDate, titleDueDate, null, null, null, Set.of());
        }
        PrescriptionRule r = rule.get();
        LocalDate base = r.getBase() == PrescriptionBase.CONTRACT_FINAL_DUE_DATE && contractFinalDueDate != null
                && contractFinalDueDate.isAfter(titleDueDate) ? contractFinalDueDate : titleDueDate;
        LocalDate start = base;
        LocalDate estimated = base.plusYears(r.getTermYears());
        Set<UUID> effective = new LinkedHashSet<>();
        List<InterruptionFact> ordered = interruptions.stream()
                .sorted(Comparator.comparing(InterruptionFact::date).thenComparing(InterruptionFact::id)).toList();
        for (InterruptionFact fact : ordered) {
            if (fact.date().isBefore(start) || fact.date().isAfter(estimated)) {
                continue;
            }
            effective.add(fact.id());
            start = fact.date();
            estimated = fact.date().plusYears(r.getTermYears());
            if (r.getInterruptionPolicy() == InterruptionPolicy.FIRST_ONLY) {
                break;
            }
        }
        return new PrescriptionCalculation(base, start, r.getId(), r.getTermYears(), estimated, effective);
    }
}
