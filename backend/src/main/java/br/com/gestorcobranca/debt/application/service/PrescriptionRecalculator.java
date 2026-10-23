package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.OriginContract;
import br.com.gestorcobranca.debt.domain.model.PrescriptionCalculation;
import br.com.gestorcobranca.debt.domain.model.PrescriptionClaim;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Mantém {@link TitlePrescription} coerente com as regras do tenant, a natureza do contrato,
 * os vencimentos e as interrupções, na transação do chamador. Acionado pela carga de títulos,
 * pela reclassificação do contrato, pelo registro de interrupção, pelo recálculo em lote após
 * alteração de regra e pela reconciliação diária.
 * <p>
 * Pretensões aplicáveis: COLLECTION sempre que houver regra para a natureza; EXECUTION somente
 * se o contrato for título executivo extrajudicial. Título sem contrato de origem não tem
 * natureza conhecida: fica sem prescrição estimada. Uma linha cuja pretensão deixou de ser
 * aplicável (regra removida, contrato reclassificado) é removida.
 * <p>
 * Concorrência: toda gravação passa por {@link #refreshContract}, sob lock do contrato. A ordem
 * de travamento é título → contrato (carga e interrupção travam o título antes); o job e a
 * reconciliação travam apenas o contrato, o que exclui ciclos.
 */
@Component
class PrescriptionRecalculator {

    static final String TRIGGER_IMPORT = "IMPORT";
    static final String TRIGGER_CLASSIFICATION = "CLASSIFICATION";
    static final String TRIGGER_INTERRUPTION = "INTERRUPTION";
    static final String TRIGGER_RULE_CHANGE = "RULE_CHANGE";
    static final String TRIGGER_RECONCILIATION = "RECONCILIATION";

    private final DebtTitleRepository titles;
    private final OriginContractRepository contracts;
    private final PrescriptionRuleRepository rules;
    private final PrescriptionInterruptionRepository interruptions;
    private final TitlePrescriptionRepository prescriptions;
    private final DebtMetrics metrics;

    PrescriptionRecalculator(DebtTitleRepository titles, OriginContractRepository contracts,
                             PrescriptionRuleRepository rules, PrescriptionInterruptionRepository interruptions,
                             TitlePrescriptionRepository prescriptions, DebtMetrics metrics) {
        this.titles = titles;
        this.contracts = contracts;
        this.rules = rules;
        this.interruptions = interruptions;
        this.prescriptions = prescriptions;
        this.metrics = metrics;
    }

    /** Contexto de cálculo de um contrato: regras aplicáveis e último vencimento. */
    private record ContractContext(Map<PrescriptionClaim, PrescriptionRule> rules, LocalDate finalDueDate) {
    }

    /** Cálculos vigentes do título por pretensão aplicável, sem gravar (usado também na consulta). */
    List<PrescriptionCalculation> calculate(DebtTitle title) {
        if (title.getOriginContractId() == null) {
            return List.of();
        }
        OriginContract contract = contracts.findById(title.getOriginContractId()).orElseThrow();
        return calculate(title, context(contract, titles.findByOriginContractIdOrderByDueDateAsc(contract.getId())));
    }

    /** Regras aplicadas aos cálculos (para a consulta exibir fundamento e validação). */
    Map<UUID, PrescriptionRule> rulesOf(List<PrescriptionCalculation> calculations) {
        return rules.findAllByIdIn(calculations.stream().map(PrescriptionCalculation::ruleId).toList()).stream()
                .collect(Collectors.toMap(PrescriptionRule::getId, Function.identity()));
    }

    /** Recalcula conforme o vínculo atual do título com contrato. @return linhas alteradas */
    int refreshFor(DebtTitle title, String trigger) {
        if (title.getOriginContractId() == null) {
            return record(trigger, sync(title, List.of()));
        }
        return refreshContract(title.getOriginContractId(), trigger);
    }

    /**
     * Recalcula todos os títulos do contrato (a data-base pode depender do último vencimento).
     * Trava o contrato: recálculos concorrentes do mesmo contrato (carga, interrupção, job de
     * regra, reconciliação) são serializados e o segundo enxerga as linhas gravadas pelo primeiro.
     */
    int refreshContract(UUID contractId, String trigger) {
        OriginContract contract = contracts.findByIdForUpdate(contractId).orElseThrow();
        List<DebtTitle> contractTitles = titles.findByOriginContractIdOrderByDueDateAsc(contractId);
        ContractContext context = context(contract, contractTitles);
        int changed = 0;
        for (DebtTitle t : contractTitles) {
            changed += sync(t, calculate(t, context));
        }
        return record(trigger, changed);
    }

    private int record(String trigger, int changed) {
        metrics.prescriptionsRecalculated(trigger, changed);
        return changed;
    }

    private ContractContext context(OriginContract contract, List<DebtTitle> contractTitles) {
        Map<PrescriptionClaim, PrescriptionRule> applicable = new EnumMap<>(PrescriptionClaim.class);
        for (PrescriptionRule rule : rules.findByInstrumentType(contract.getInstrumentType())) {
            if (rule.getClaim() != PrescriptionClaim.EXECUTION || contract.isExecutiveTitle()) {
                applicable.put(rule.getClaim(), rule);
            }
        }
        LocalDate finalDueDate = contractTitles.stream().map(DebtTitle::getDueDate).max(Comparator.naturalOrder())
                .orElse(null);
        return new ContractContext(applicable, finalDueDate);
    }

    private List<PrescriptionCalculation> calculate(DebtTitle title, ContractContext context) {
        if (context.rules().isEmpty()) {
            return List.of();
        }
        List<InterruptionFact> facts = interruptionsOf(title.getId()).stream()
                .map(i -> new InterruptionFact(i.getId(), i.getInterruptionDate())).toList();
        List<PrescriptionCalculation> result = new ArrayList<>();
        context.rules().values().forEach(rule ->
                result.add(PrescriptionCalculator.calculate(title.getDueDate(), context.finalDueDate(), rule, facts)));
        return result;
    }

    /** Grava os cálculos e remove pretensões que deixaram de ser aplicáveis. @return linhas alteradas */
    private int sync(DebtTitle title, List<PrescriptionCalculation> calculations) {
        Map<PrescriptionClaim, TitlePrescription> existing = new EnumMap<>(PrescriptionClaim.class);
        prescriptions.findByTitleId(title.getId()).forEach(p -> existing.put(p.getClaim(), p));
        int changed = 0;
        for (PrescriptionCalculation c : calculations) {
            TitlePrescription current = existing.remove(c.claim());
            if (current == null) {
                prescriptions.save(new TitlePrescription(title.getId(), c));
                changed++;
            } else if (current.apply(c)) {
                prescriptions.save(current);
                changed++;
            }
        }
        for (TitlePrescription stale : existing.values()) {
            prescriptions.delete(stale);
            changed++;
        }
        return changed;
    }

    /** Interrupções do título na ordem cronológica. */
    List<PrescriptionInterruption> interruptionsOf(UUID titleId) {
        return interruptions.findByTitleIdOrderByInterruptionDateAscCreatedAtAsc(titleId);
    }
}
