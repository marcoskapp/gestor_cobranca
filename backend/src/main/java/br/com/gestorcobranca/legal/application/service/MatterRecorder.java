package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.legal.domain.model.ClosureReason;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.model.LegalSettings;
import br.com.gestorcobranca.legal.domain.model.LegalStage;
import br.com.gestorcobranca.legal.domain.model.StageHistoryEntry;
import br.com.gestorcobranca.legal.domain.model.StageRequirements;
import br.com.gestorcobranca.legal.domain.repository.LegalMatterRepository;
import br.com.gestorcobranca.legal.domain.repository.StageHistoryRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.legal.LegalMatterStageChanged;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Gravação do nascimento e das mudanças de fase do caso jurídico, com todos os efeitos na mesma
 * transação: linha do tempo, auditoria, evento de negócio, evento de integração
 * {@code LegalMatterStageChanged}, métrica, retenção do caso de cobrança no collection e, no
 * encerramento, cancelamento dos prazos pendentes e prazos de baixa de restrições após o pagamento.
 */
@Component
class MatterRecorder {

    static final String ENTITY = LegalAccess.MATTER;
    private static final Set<ClosureReason> PAID = EnumSet.of(ClosureReason.PAID, ClosureReason.AGREEMENT_FULFILLED,
            ClosureReason.ASSET_RECOVERED);

    private final LegalMatterRepository matters;
    private final StageHistoryRepository history;
    private final CollectionHold collectionHold;
    private final DeadlineRecorder deadlines;
    private final LegalSettingsProvider settings;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final ApplicationEventPublisher events;
    private final LegalMetrics metrics;
    private final Clock clock;

    MatterRecorder(LegalMatterRepository matters, StageHistoryRepository history, CollectionHold collectionHold,
                   DeadlineRecorder deadlines, LegalSettingsProvider settings, AuditTrail auditTrail,
                   BusinessEventLogger businessEvents, ApplicationEventPublisher events, LegalMetrics metrics,
                   Clock clock) {
        this.matters = matters;
        this.history = history;
        this.collectionHold = collectionHold;
        this.deadlines = deadlines;
        this.settings = settings;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    LegalMatter referred(LegalMatter matter, List<String> excludedTitles, String cause) {
        matters.save(matter);
        history.save(new StageHistoryEntry(matter.getId(), null, LegalStage.ELIGIBLE, matter.getReferralNote(), null,
                cause, matter.getReferredAt()));
        auditTrail.record(AuditEntry.of("legal_matter.referred", ENTITY, matter.getId())
                .creditor(matter.getCreditorId())
                .created("collectionCaseId", matter.getCollectionCaseId())
                .created("origin", matter.getOrigin())
                .created("eligibilityRuleId", matter.getEligibilityRuleId())
                .created("titleIds", matter.getTitleIds().stream().sorted().toList().toString())
                .created("debtAmount", matter.getDebtAmount())
                .created("flags", LegalViews.names(matter.getFlags()).toString())
                .meta("excludedTitles", excludedTitles.isEmpty() ? null : excludedTitles.toString())
                .reason(matter.getReferralNote()));
        businessEvents.log(base(BusinessEventTypes.LEGAL_MATTER_REFERRED, matter).amount(matter.getDebtAmount())
                .attr("origin", matter.getOrigin()).attr("ruleId", matter.getEligibilityRuleId())
                .attr("titles", matter.getTitleIds().size()).attr("excludedTitles", excludedTitles.size())
                .attr("flags", String.join(",", LegalViews.names(matter.getFlags())))
                .attr("prescribedExcluded", matter.getFlags().contains(
                        br.com.gestorcobranca.legal.domain.model.MatterFlag.PRESCRIBED_TITLES_EXCLUDED)));
        publish(matter, null);
        metrics.referred(matter.getOrigin());
        return matter;
    }

    /** Transição validada pelo domínio e seus efeitos. @return a fase anterior */
    LegalStage changeStage(LegalMatter matter, LegalStage target, StageRequirements req, String reason, String cause) {
        Instant now = Instant.now(clock);
        LegalStage previous = matter.moveTo(target, req, reason, now);
        after(matter, previous, cause);
        return previous;
    }

    /** Quebra/cancelamento do acordo judicial: retorno à fase processual guardada. */
    LegalStage resumeAfterAgreement(LegalMatter matter, String reason, String cause) {
        LegalStage previous = matter.resumeAfterAgreement(reason, Instant.now(clock));
        after(matter, previous, cause);
        return previous;
    }

    private void after(LegalMatter matter, LegalStage previous, String cause) {
        LegalSettings.Values values = settings.current();
        collectionHold.sync(matter, values);
        matters.save(matter);
        history.save(new StageHistoryEntry(matter.getId(), previous, matter.getStage(), matter.getStageReason(),
                matter.getStage() == LegalStage.CLOSED ? matter.getClosureReason() : null, cause,
                matter.getStageChangedAt()));
        auditTrail.record(AuditEntry.of("legal_matter.stage_changed", ENTITY, matter.getId())
                .creditor(matter.getCreditorId()).reason(matter.getStageReason())
                .change("stage", previous, matter.getStage())
                .change("closureReason", null, matter.getStage() == LegalStage.CLOSED ? matter.getClosureReason() : null)
                .meta("agreementId", matter.getStage() == LegalStage.SETTLED_IN_COURT ? matter.getAgreementId() : null)
                .meta("collectionHold", matter.isCollectionHold())
                .meta("cause", cause));
        businessEvents.log(base(BusinessEventTypes.LEGAL_STAGE_CHANGED, matter).amount(matter.getDebtAmount())
                .attr("from", previous).attr("to", matter.getStage()).attr("route", matter.getPrimaryRoute())
                .attr("responsibleType", matter.getResponsibleType()).attr("lawFirmId", matter.getLawFirmId())
                .attr("cause", cause).attr("collectionHold", matter.isCollectionHold()));
        if (matter.getStage().isTerminal()) {
            closed(matter, previous, values);
        }
        publish(matter, previous);
        metrics.transition(previous, matter.getStage());
    }

    private void closed(LegalMatter matter, LegalStage previous, LegalSettings.Values values) {
        int cancelled = deadlines.cancelPending(matter, "Caso jurídico " + matter.getStage().name());
        int removals = matter.getStage() == LegalStage.CLOSED && PAID.contains(matter.getClosureReason())
                ? deadlines.removalDeadlines(matter, values.removalDeadlineBusinessDays(), "closure") : 0;
        Duration duration = Duration.between(matter.getReferredAt(), Instant.now(clock));
        businessEvents.log(base(BusinessEventTypes.LEGAL_MATTER_CLOSED, matter).amount(matter.getRecoveredAmount())
                .durationMs(duration.toMillis())
                .attr("stage", matter.getStage()).attr("from", previous).attr("closureReason", matter.getClosureReason())
                .attr("route", matter.getPrimaryRoute()).attr("durationDays", duration.toDays())
                .attr("debtAmount", matter.getDebtAmount().amount())
                .attr("expensesTotal", matter.getExpensesTotal().amount())
                .attr("recoveredAmount", matter.getRecoveredAmount().amount())
                .attr("responsibleType", matter.getResponsibleType()).attr("lawFirmId", matter.getLawFirmId())
                .attr("deadlinesCancelled", cancelled).attr("removalDeadlines", removals));
    }

    BusinessEvent base(String type, LegalMatter matter) {
        return BusinessEvent.of(type).creditor(matter.getCreditorId()).portfolio(matter.getPortfolioId())
                .debtor(matter.getDebtorId()).collectionCase(matter.getCollectionCaseId())
                .reference(ENTITY, matter.getId());
    }

    private void publish(LegalMatter matter, LegalStage previous) {
        events.publishEvent(new LegalMatterStageChanged(Ids.newId(), matter.getTenantId(), Instant.now(clock),
                matter.getId(), matter.getCollectionCaseId(), matter.getDebtorId(), matter.getCreditorId(),
                matter.getPortfolioId(), matter.getTitleIds().stream().sorted().toList(),
                previous == null ? null : previous.name(), matter.getStage().name(),
                LegalViews.name(matter.getPrimaryRoute()), matter.getResponsibleType().name(), matter.getLawFirmId(),
                matter.getStage() == LegalStage.CLOSED ? LegalViews.name(matter.getClosureReason()) : null,
                matter.getDebtAmount().amount()));
    }
}
