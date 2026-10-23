package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.AssignmentView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputedTitle;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceView;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.InteractionView;
import br.com.gestorcobranca.collection.application.port.in.PromiseUseCases.PromiseView;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases.QueueView;
import br.com.gestorcobranca.collection.application.port.in.TabulationUseCases.TabulationView;
import br.com.gestorcobranca.collection.application.port.in.TaskUseCases.TaskView;
import br.com.gestorcobranca.collection.domain.model.CaseAssignment;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.CollectionQueue;
import br.com.gestorcobranca.collection.domain.model.CollectionTask;
import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DisputeEvidence;
import br.com.gestorcobranca.collection.domain.model.Eligibility;
import br.com.gestorcobranca.collection.domain.model.Interaction;
import br.com.gestorcobranca.collection.domain.model.PaymentPromise;
import br.com.gestorcobranca.collection.domain.model.Tabulation;
import br.com.gestorcobranca.collection.domain.model.TaskStatus;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Money;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Conversão das entidades do módulo nas views públicas (nunca expõem entidades). */
final class CollectionViews {

    private CollectionViews() {
    }

    static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }

    static CaseView caseView(CollectionCase c, LocalDate today) {
        Eligibility eligibility = c.eligibility();
        return new CaseView(c.getId(), c.getDebtorId(), c.getCreditorId(), c.getPortfolioId(), c.getStatus().name(),
                CollectionEnums.name(c.getResumeStatus()), c.getStatusReason(), c.getOpenedAt(), c.getClosedAt(),
                CollectionEnums.name(c.getCloseReason()), amount(c.getOpenBalance()), amount(c.getEligibleBalance()),
                c.getOpenTitles(), c.getSuspendedTitles(), c.getDisputedTitles(), c.getOldestDueDate(),
                c.daysOverdue(today), c.isEligible(),
                eligibility.reasons().stream().map(Enum::name).sorted().toList(),
                eligibility.contactableChannels().stream().map(Enum::name).sorted().toList(),
                c.getEligibilityEvaluatedAt(), c.getQueueId(), c.getOwnerType().name(), c.getAssigneeUserId(),
                c.getAssigneeTeamId(), c.getAssignedAt(), c.getNextActionAt(), c.getLastInteractionAt(),
                c.getLastEffectiveContactAt(), c.getInteractionCount(), c.getEffectiveContactCount(), c.getScore(),
                version(c.getVersion()));
    }

    static AssignmentView assignment(CaseAssignment a) {
        return new AssignmentView(a.getId(), a.getCaseId(), a.getMode().name(), a.getOwnerType().name(), a.getUserId(),
                a.getTeamId(), a.getQueueId(), a.getPreviousOwnerType().name(), a.getPreviousUserId(), a.getReason(),
                a.getValidFrom(), a.getValidTo(), a.getCreatedBy());
    }

    static QueueView queue(CollectionQueue q) {
        return new QueueView(q.getId(), q.getCode(), q.getName(), q.getDescription(), q.isActive(), q.getPriority(),
                q.getCreditorId(), q.getPortfolioId(), q.getMinDaysOverdue(), q.getMaxDaysOverdue(),
                amount(q.getMinBalance()), amount(q.getMaxBalance()), q.getMinScore(), q.getMaxScore(),
                q.getOrdering().name(), q.getStrategy().name(), q.getMaxCasesPerUser(), q.getTeamIds(), q.getUpdatedAt(),
                version(q.getVersion()));
    }

    static TabulationView tabulation(Tabulation t) {
        return new TabulationView(t.getId(), t.getCode(), t.getName(), t.isEffectiveContact(), t.isPromise(),
                t.isCallback(), t.isRequiresNote(), t.getDisplayOrder(), t.isActive(), version(t.getVersion()));
    }

    static InteractionView interaction(Interaction i) {
        return new InteractionView(i.getId(), i.getCaseId(), i.getDebtorId(), i.getCreditorId(), i.getPortfolioId(),
                i.getChannel().name(), i.getDirection().name(), i.getTabulationId(), i.getOutcomeCode(),
                i.isEffectiveContact(), i.getNote(), i.getContactId(), i.getStartedAt(), i.getEndedAt(),
                i.getDurationSeconds(), i.getOperatorId(), i.getActorType(), i.getSource().name(), i.getExternalRef(),
                i.getCreatedAt());
    }

    static TaskView task(CollectionTask t, UUID debtorId) {
        return task(t, debtorId, null);
    }

    static TaskView task(CollectionTask t, UUID debtorId, String debtorName) {
        Boolean onTime = t.getStatus() == TaskStatus.DONE
                ? !t.wasMissed() && !t.getCompletedAt().isAfter(t.getToleranceUntil()) : null;
        LocalDateTime local = LocalDateTime.ofInstant(t.getDueAt(), TimeConfig.BUSINESS_ZONE);
        return new TaskView(t.getId(), t.getCaseId(), t.getCreditorId(), debtorId, debtorName, t.getType().name(),
                t.getStatus().name(),
                t.getDueAt(), local, t.getToleranceUntil(), t.getAssigneeUserId(), t.getNote(),
                t.getOriginInteractionId(), t.getCompletedAt(), t.getCompletedBy(), t.getCompletionInteractionId(), onTime,
                t.getMissedAt(), t.getCancelReason(), version(t.getVersion()));
    }

    static PromiseView promise(PaymentPromise p) {
        return new PromiseView(p.getId(), p.getCaseId(), p.getDebtorId(), p.getCreditorId(), p.getPortfolioId(),
                amount(p.getAmount()), p.getDueDate(), p.getBreaksAfter(), p.getPromisedAt(), p.getStatus().name(),
                amount(p.getPaidAmount()), p.getOriginInteractionId(), p.getOperatorId(), p.getResolvedAt(),
                p.getResolutionReason(), version(p.getVersion()));
    }

    static DisputeView dispute(Dispute d, int evidenceCount, List<DisputedTitle> titles) {
        return new DisputeView(d.getId(), d.getCaseId(), d.getDebtorId(), d.getCreditorId(), d.getPortfolioId(),
                d.getTitleIds(), titles, d.getType().name(), d.getOrigin().name(), d.getStatus().name(), d.getDescription(),
                d.isBlocksCollection(), amount(d.getDisputedAmount()), d.getOpenedAt(), d.getResponseDueDate(),
                d.isOverdue(), CollectionEnums.name(d.getOutcome()), d.getResolutionNote(), d.getResolvedAt(),
                d.getResolvedBy(), evidenceCount, version(d.getVersion()));
    }

    static EvidenceView evidence(DisputeEvidence e, String createdByName) {
        return new EvidenceView(e.getId(), e.getDisputeId(), e.getFileName(), e.getContentType(), e.getSize(),
                e.getSha256(), e.getCreatedAt(), e.getCreatedBy(), createdByName);
    }

    static BigDecimal amount(Money money) {
        return money == null ? null : money.amount();
    }

    private static long version(Long version) {
        return version == null ? 0 : version;
    }
}
