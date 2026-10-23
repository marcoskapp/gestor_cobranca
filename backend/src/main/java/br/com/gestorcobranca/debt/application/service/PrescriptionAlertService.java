package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.DebtProperties;
import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.OriginContract;
import br.com.gestorcobranca.debt.domain.model.PrescriptionRule;
import br.com.gestorcobranca.debt.domain.model.TitlePrescription;
import br.com.gestorcobranca.debt.domain.repository.DebtTitleRepository;
import br.com.gestorcobranca.debt.domain.repository.OriginContractRepository;
import br.com.gestorcobranca.debt.domain.repository.PrescriptionRuleRepository;
import br.com.gestorcobranca.debt.domain.repository.TitlePrescriptionRepository;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.debt.TitlePrescriptionApproaching;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Varredura diária da prescrição no tenant do contexto, em lotes (cada lote na transação aberta
 * por {@code TenantExecution}):
 * <ol>
 *   <li>reconciliação: calcula a prescrição de contratos com título em cobrança ainda sem ela
 *       (títulos anteriores às regras, regras recém-semeadas);</li>
 *   <li>alerta: para cada prescrição de título em cobrança que entrou na janela da sua regra e
 *       ainda não foi alertada para a data estimada vigente, publica
 *       {@link TitlePrescriptionApproaching} e o evento de negócio
 *       {@code title.prescription_approaching}. Uma nova data (interrupção, mudança de regra)
 *       gera novo alerta.</li>
 * </ol>
 */
@Component
class PrescriptionAlertService {

    private final TitlePrescriptionRepository prescriptions;
    private final DebtTitleRepository titles;
    private final OriginContractRepository contracts;
    private final PrescriptionRuleRepository rules;
    private final PrescriptionRecalculator recalculator;
    private final BusinessEventLogger businessEvents;
    private final ApplicationEventPublisher events;
    private final CurrentActor currentActor;
    private final DebtMetrics metrics;
    private final DebtProperties properties;
    private final Clock clock;

    PrescriptionAlertService(TitlePrescriptionRepository prescriptions, DebtTitleRepository titles,
                             OriginContractRepository contracts, PrescriptionRuleRepository rules,
                             PrescriptionRecalculator recalculator, BusinessEventLogger businessEvents,
                             ApplicationEventPublisher events, CurrentActor currentActor, DebtMetrics metrics,
                             DebtProperties properties, Clock clock) {
        this.prescriptions = prescriptions;
        this.titles = titles;
        this.contracts = contracts;
        this.rules = rules;
        this.recalculator = recalculator;
        this.businessEvents = businessEvents;
        this.events = events;
        this.currentActor = currentActor;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    int batchSize() {
        return properties.prescription().batchSize();
    }

    /**
     * @return contratos reconciliados neste lote (zero se nada pôde ser calculado, o que encerra
     *         a repetição do chamador)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    int reconcileBatch() {
        List<UUID> contractIds = prescriptions.findContractIdsMissingPrescription(batchSize());
        int changed = 0;
        for (UUID id : contractIds) {
            changed += recalculator.refreshContract(id, PrescriptionRecalculator.TRIGGER_RECONCILIATION);
        }
        return changed == 0 ? 0 : contractIds.size();
    }

    /** @return alertas emitidos neste lote */
    @Transactional(propagation = Propagation.MANDATORY)
    int alertBatch() {
        LocalDate today = DebtViews.today(clock);
        Instant now = Instant.now(clock);
        List<TitlePrescription> due = prescriptions.findAlertCandidates(today, batchSize());
        for (TitlePrescription prescription : due) {
            DebtTitle title = titles.findById(prescription.getTitleId()).orElseThrow();
            OriginContract contract = contracts.findById(title.getOriginContractId()).orElseThrow();
            PrescriptionRule rule = rules.findById(prescription.getRuleId()).orElseThrow();
            long daysRemaining = ChronoUnit.DAYS.between(today, prescription.getEstimatedDate());
            events.publishEvent(new TitlePrescriptionApproaching(Ids.newId(), currentActor.tenantId(), now,
                    title.getId(), title.getDebtorId(), title.getCreditorId(), title.getPortfolioId(), contract.getId(),
                    contract.getInstrumentType().name(), contract.isExecutiveTitle(), prescription.getClaim().name(),
                    prescription.getEstimatedDate(), daysRemaining, rule.isValidated(), title.getOpenBalance().amount()));
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.TITLE_PRESCRIPTION_APPROACHING)
                    .creditor(title.getCreditorId()).portfolio(title.getPortfolioId()).debtor(title.getDebtorId())
                    .reference("debt_title", title.getId()).amount(title.getOpenBalance())
                    .attr("claim", prescription.getClaim()).attr("instrumentType", contract.getInstrumentType())
                    .attr("executiveTitle", contract.isExecutiveTitle())
                    .attr("estimatedDate", prescription.getEstimatedDate().toString())
                    .attr("daysRemaining", daysRemaining).attr("ruleValidated", rule.isValidated())
                    .attr("titleStatus", title.getStatus()));
            prescription.markAlerted(now);
            prescriptions.save(prescription);
            metrics.prescriptionAlert(prescription.getClaim(), contract.getInstrumentType());
        }
        return due.size();
    }
}
