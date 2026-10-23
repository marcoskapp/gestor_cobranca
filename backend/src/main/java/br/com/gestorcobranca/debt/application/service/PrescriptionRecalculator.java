package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.OriginContract;
import br.com.gestorcobranca.debt.domain.model.PrescriptionCalculation;
import br.com.gestorcobranca.debt.domain.model.PrescriptionInterruption;
import br.com.gestorcobranca.debt.domain.model.PrescriptionRule;
import br.com.gestorcobranca.debt.domain.model.TitlePrescription;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.debt.domain.repository.OriginContractRepository;
import br.com.gestorcobranca.debt.domain.repository.PrescriptionInterruptionRepository;
import br.com.gestorcobranca.debt.domain.repository.PrescriptionRuleRepository;
import br.com.gestorcobranca.debt.domain.repository.TitlePrescriptionRepository;
import br.com.gestorcobranca.debt.domain.service.PrescriptionCalculator;
import br.com.gestorcobranca.debt.domain.service.PrescriptionCalculator.InterruptionFact;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Mantém {@link TitlePrescription} coerente com a regra do tenant, a natureza do contrato, os
 * vencimentos e as interrupções, na transação do chamador. Acionado pela carga de títulos, pela
 * reclassificação do contrato, pelo registro de interrupção e pelo recálculo em lote após
 * alteração de regra ({@code PrescriptionRecalculationJob}).
 * <p>
 * Título sem contrato de origem não tem natureza conhecida: fica sem regra e sem data estimada.
 */
@Component
class PrescriptionRecalculator {

    private final DebtTitleRepository titles;
    private final OriginContractRepository contracts;
    private final PrescriptionRuleRepository rules;
    private final PrescriptionInterruptionRepository interruptions;
    private final TitlePrescriptionRepository prescriptions;

    PrescriptionRecalculator(DebtTitleRepository titles, OriginContractRepository contracts,
                             PrescriptionRuleRepository rules, PrescriptionInterruptionRepository interruptions,
                             TitlePrescriptionRepository prescriptions) {
        this.titles = titles;
        this.contracts = contracts;
        this.rules = rules;
        this.interruptions = interruptions;
        this.prescriptions = prescriptions;
    }

    /** Cálculo vigente do título, sem gravar (usado também na consulta). */
    PrescriptionCalculation calculate(DebtTitle title) {
        if (title.getOriginContractId() == null) {
            return calculate(title, null, Optional.empty());
        }
        OriginContract contract = contracts.findById(title.getOriginContractId()).orElseThrow();
        List<DebtTitle> contractTitles = titles.findByOriginContractIdOrderByDueDateAsc(contract.getId());
        return calculate(title, finalDueDate(contractTitles), rules.findByInstrumentType(contract.getInstrumentType()));
    }

    /** Recalcula e grava a prescrição do título. */
    TitlePrescription refresh(DebtTitle title) {
        return save(title, calculate(title));
    }

    /** Recalcula todos os títulos do contrato (a data-base pode depender do último vencimento). */
    void refreshContract(UUID contractId) {
        OriginContract contract = contracts.findById(contractId).orElseThrow();
        Optional<PrescriptionRule> rule = rules.findByInstrumentType(contract.getInstrumentType());
        List<DebtTitle> contractTitles = titles.findByOriginContractIdOrderByDueDateAsc(contractId);
        LocalDate finalDueDate = finalDueDate(contractTitles);
        contractTitles.forEach(t -> save(t, calculate(t, finalDueDate, rule)));
    }

    /** Recalcula conforme o vínculo atual do título com contrato. */
    void refreshFor(DebtTitle title) {
        if (title.getOriginContractId() == null) {
            refresh(title);
        } else {
            refreshContract(title.getOriginContractId());
        }
    }

    private PrescriptionCalculation calculate(DebtTitle title, LocalDate finalDueDate, Optional<PrescriptionRule> rule) {
        List<InterruptionFact> facts = interruptions.findByTitleIdOrderByInterruptionDateAscCreatedAtAsc(title.getId())
                .stream().map(i -> new InterruptionFact(i.getId(), i.getInterruptionDate())).toList();
        return PrescriptionCalculator.calculate(title.getDueDate(), finalDueDate, rule, facts);
    }

    private TitlePrescription save(DebtTitle title, PrescriptionCalculation calculation) {
        Optional<TitlePrescription> existing = prescriptions.findByTitleId(title.getId());
        if (existing.isEmpty()) {
            return prescriptions.save(new TitlePrescription(title.getId(), calculation));
        }
        TitlePrescription prescription = existing.get();
        return prescription.apply(calculation) ? prescriptions.save(prescription) : prescription;
    }

    private static LocalDate finalDueDate(List<DebtTitle> contractTitles) {
        return contractTitles.stream().map(DebtTitle::getDueDate).max(Comparator.naturalOrder()).orElse(null);
    }

    /** Interrupções do título com indicação de eficácia, na ordem cronológica. */
    List<PrescriptionInterruption> interruptionsOf(UUID titleId) {
        return interruptions.findByTitleIdOrderByInterruptionDateAscCreatedAtAsc(titleId);
    }
}
