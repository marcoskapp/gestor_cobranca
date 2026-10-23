package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.legal.domain.model.AgreementRecord;
import br.com.gestorcobranca.legal.domain.model.ClosureReason;
import br.com.gestorcobranca.legal.domain.model.LegalEligibilityRule;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.model.LegalStage;
import br.com.gestorcobranca.legal.domain.model.MatterFlag;
import br.com.gestorcobranca.legal.domain.model.MatterOrigin;
import br.com.gestorcobranca.legal.domain.repository.AgreementRecordRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalEligibilityRuleRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalMatterRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.events.debt.TitlePrescriptionApproaching;
import br.com.gestorcobranca.shared.events.debt.TitleStatusChanged;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reações do jurídico a eventos de outros módulos (executadas como ator de sistema do tenant,
 * dentro da transação do consumidor idempotente):
 * <ul>
 *   <li>{@code AgreementStatusChanged} (negotiation, referenciado por id): acordo formalizado
 *       sobre títulos de um caso ajuizado leva a SETTLED_IN_COURT (com prazo de homologação);
 *       quitado encerra o caso jurídico (AGREEMENT_FULFILLED); quebrado ou cancelado retoma a fase
 *       processual; renegociação transfere o vínculo. Quebras alimentam o critério "acordos quebrados".</li>
 *   <li>{@code TitleStatusChanged} (debt): todos os títulos do caso jurídico fora de cobrança →
 *       sinalização e prazos de baixa de protesto/negativação efetivos.</li>
 *   <li>{@code TitlePrescriptionApproaching} (debt): sinaliza o caso jurídico aberto ou avalia a
 *       indicação pelas regras com critério de prescrição próxima.</li>
 * </ul>
 */
@Component
@Transactional
class LegalReactions {

    private static final Logger LOG = LoggerFactory.getLogger(LegalReactions.class);
    private static final Set<String> OUT_OF_COLLECTION = Set.of("SETTLED", "CANCELLED", "RETURNED");
    private static final Set<String> IN_COLLECTION = Set.of("OPEN", "SUSPENDED");

    private final AgreementRecordRepository agreements;
    private final LegalMatterRepository matters;
    private final LegalEligibilityRuleRepository rules;
    private final CaseUseCases cases;
    private final DebtTitleUseCases titles;
    private final MatterRecorder recorder;
    private final MatterRequirements requirements;
    private final AgreementLinks links;
    private final DeadlineRecorder deadlines;
    private final Referrals referrals;
    private final LegalSettingsProvider settings;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final LegalMetrics metrics;

    LegalReactions(AgreementRecordRepository agreements, LegalMatterRepository matters, LegalEligibilityRuleRepository rules,
                   CaseUseCases cases, DebtTitleUseCases titles, MatterRecorder recorder, MatterRequirements requirements,
                   AgreementLinks links, DeadlineRecorder deadlines, Referrals referrals, LegalSettingsProvider settings,
                   AuditTrail auditTrail, BusinessEventLogger businessEvents, LegalMetrics metrics) {
        this.agreements = agreements;
        this.matters = matters;
        this.rules = rules;
        this.cases = cases;
        this.titles = titles;
        this.recorder = recorder;
        this.requirements = requirements;
        this.links = links;
        this.deadlines = deadlines;
        this.referrals = referrals;
        this.settings = settings;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
    }

    // ---------------------------------------------------------------- acordos (negotiation)

    void agreementStatusChanged(AgreementStatusChanged e) {
        metrics.eventConsumed("agreement_status");
        AgreementRecord record = agreements.findByAgreementId(e.agreementId()).orElseGet(() -> new AgreementRecord(
                e.agreementId(), e.caseId(), e.debtorId(), e.creditorId(), e.portfolioId(), e.replacedAgreementId()));
        boolean applied = record.apply(e.newStatus(), e.occurredAt());
        agreements.save(record);
        if (!applied) {
            return;
        }
        Optional<LegalMatter> found = matterOf(e);
        if (found.isEmpty()) {
            return;
        }
        LegalMatter m = matters.findByIdForUpdate(found.get().getId()).orElseThrow();
        switch (e.newStatus()) {
            case "FORMALIZED" -> formalized(m, record, e);
            case "SETTLED" -> {
                if (e.agreementId().equals(m.getAgreementId()) || m.getId().equals(record.getMatterId())) {
                    recorder.changeStage(m, LegalStage.CLOSED, requirements.of(m, null, ClosureReason.AGREEMENT_FULFILLED),
                            "Acordo " + e.agreementId() + " quitado", "agreement");
                }
            }
            case "BROKEN", "CANCELLED" -> {
                if (m.getStage() == LegalStage.SETTLED_IN_COURT && e.agreementId().equals(m.getAgreementId())) {
                    recorder.resumeAfterAgreement(m, "Acordo judicial " + e.agreementId() + " "
                            + ("BROKEN".equals(e.newStatus()) ? "quebrado" : "cancelado") + ": retomada do processo",
                            "agreement");
                    businessEvents.log(recorder.base(BusinessEventTypes.LEGAL_AGREEMENT_BROKEN, m)
                            .amount(e.totalAmount()).attr("agreementId", e.agreementId())
                            .attr("status", e.newStatus()).attr("resumedStage", m.getStage()));
                } else {
                    m.clearAgreement(e.agreementId());
                    matters.save(m);
                }
            }
            default -> LOG.atDebug().addKeyValue("status", e.newStatus()).log("Situação de acordo sem efeito no jurídico");
        }
    }

    private void formalized(LegalMatter m, AgreementRecord record, AgreementStatusChanged e) {
        if (m.getStage().isTerminal() || m.getStage() == LegalStage.NOT_VIABLE) {
            return;
        }
        if (e.replacedAgreementId() != null && e.replacedAgreementId().equals(m.getAgreementId())) {
            m.replaceAgreement(e.replacedAgreementId(), e.agreementId());
            matters.save(m);
            links.link(m, record, m.getStage() == LegalStage.SETTLED_IN_COURT, "renegotiation");
            return;
        }
        if (m.getStage().isJudicial() && m.getStage() != LegalStage.SETTLED_IN_COURT) {
            recorder.changeStage(m, LegalStage.SETTLED_IN_COURT, requirements.of(m, e.agreementId(), null),
                    "Acordo " + e.agreementId() + " formalizado em processo ajuizado", "agreement");
            links.link(m, record, true, "agreement");
        } else if (m.getStage() != LegalStage.SETTLED_IN_COURT) {
            m.trackAgreement(e.agreementId());
            matters.save(m);
            links.link(m, record, false, "agreement");
        }
    }

    /** Caso jurídico aberto do caso de cobrança do acordo, ou que contém algum título do acordo. */
    private Optional<LegalMatter> matterOf(AgreementStatusChanged e) {
        if (e.caseId() != null) {
            Optional<LegalMatter> byCase = matters.findOpenByCollectionCaseId(e.caseId());
            if (byCase.isPresent()) {
                return byCase;
            }
        }
        return e.titleIds().stream().flatMap(t -> matters.findOpenByTitleId(t).stream())
                .filter(m -> m.getCreditorId().equals(e.creditorId())).findFirst();
    }

    // ---------------------------------------------------------------- títulos (debt)

    void titleStatusChanged(TitleStatusChanged e) {
        metrics.eventConsumed("title_status");
        if (!OUT_OF_COLLECTION.contains(e.newStatus())) {
            return;
        }
        for (LegalMatter open : matters.findOpenByTitleId(e.titleId())) {
            LegalMatter m = matters.findByIdForUpdate(open.getId()).orElseThrow();
            Map<String, Integer> statuses = new LinkedHashMap<>();
            boolean allOut = true;
            for (UUID titleId : m.getTitleIds()) {
                String status = titles.get(titleId).status();
                statuses.merge(status, 1, Integer::sum);
                allOut &= !IN_COLLECTION.contains(status);
            }
            if (!allOut || !m.flag(MatterFlag.TITLES_SETTLED)) {
                continue;
            }
            int removals = statuses.containsKey("SETTLED")
                    ? deadlines.removalDeadlines(m, settings.current().removalDeadlineBusinessDays(), "titles_settled") : 0;
            matters.save(m);
            auditTrail.record(AuditEntry.of("legal_matter.titles_settled", MatterRecorder.ENTITY, m.getId())
                    .creditor(m.getCreditorId()).meta("titleStatuses", statuses.toString())
                    .meta("removalDeadlines", removals));
            businessEvents.log(recorder.base(BusinessEventTypes.LEGAL_TITLES_SETTLED, m).amount(m.getDebtAmount())
                    .attr("stage", m.getStage()).attr("titleStatuses", statuses.toString())
                    .attr("removalDeadlines", removals));
        }
    }

    // ---------------------------------------------------------------- prescrição (debt)

    void prescriptionApproaching(TitlePrescriptionApproaching e) {
        metrics.eventConsumed("prescription_approaching");
        List<LegalMatter> open = matters.findOpenByTitleId(e.titleId());
        if (!open.isEmpty()) {
            for (LegalMatter found : open) {
                LegalMatter m = matters.findByIdForUpdate(found.getId()).orElseThrow();
                if (m.flag(MatterFlag.PRESCRIPTION_NEAR)) {
                    matters.save(m);
                }
                businessEvents.log(recorder.base(BusinessEventTypes.LEGAL_PRESCRIPTION_ALERT, m).amount(e.openBalance())
                        .attr("titleId", e.titleId()).attr("claim", e.claim()).attr("estimatedDate", e.estimatedDate().toString())
                        .attr("daysRemaining", e.daysRemaining()).attr("ruleValidated", e.ruleValidated())
                        .attr("stage", m.getStage()));
            }
            return;
        }
        List<LegalEligibilityRule> applicable = rules.findByCreditorIdAndActiveTrueOrderByCreatedAtAsc(e.creditorId()).stream()
                .filter(r -> r.getPrescriptionWithinDays() != null && r.appliesTo(e.creditorId(), e.portfolioId()))
                .toList();
        if (applicable.isEmpty()) {
            return;
        }
        cases.findActive(e.debtorId(), e.portfolioId())
                .ifPresent(c -> referrals.referAutomatically(c, applicable, MatterOrigin.PRESCRIPTION_ALERT));
    }
}
