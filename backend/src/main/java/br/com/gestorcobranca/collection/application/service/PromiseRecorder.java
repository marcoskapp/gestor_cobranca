package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.PaymentPromise;
import br.com.gestorcobranca.collection.domain.model.PromiseStatus;
import br.com.gestorcobranca.collection.domain.repository.PaymentPromiseRepository;
import br.com.gestorcobranca.collection.domain.service.OperationBands;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.collection.PromiseStatusChanged;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Persistência das mudanças de promessa com os efeitos obrigatórios: auditoria,
 * {@link PromiseStatusChanged}, eventos de negócio ({@code promise.registered}, {@code promise.kept},
 * {@code promise.broken}) e métricas {@code collection.promises}.
 */
@Component
class PromiseRecorder {

    private final PaymentPromiseRepository promises;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final ApplicationEventPublisher events;
    private final CurrentActor currentActor;
    private final CollectionMetrics metrics;
    private final Clock clock;

    PromiseRecorder(PaymentPromiseRepository promises, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                    ApplicationEventPublisher events, CurrentActor currentActor, CollectionMetrics metrics, Clock clock) {
        this.promises = promises;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.events = events;
        this.currentActor = currentActor;
        this.metrics = metrics;
        this.clock = clock;
    }

    PaymentPromise registered(PaymentPromise p, CollectionCase c, String channel) {
        PaymentPromise saved = promises.saveAndFlush(p);
        auditTrail.record(AuditEntry.of("promise.registered", "promise", saved.getId())
                .creditor(saved.getCreditorId())
                .created("caseId", saved.getCaseId())
                .created("amount", saved.getAmount())
                .created("dueDate", saved.getDueDate())
                .created("breaksAfter", saved.getBreaksAfter()));
        businessEvents.log(CaseRecorder.base(BusinessEventTypes.PROMISE_REGISTERED, c, CollectionViews.today(clock))
                .reference("promise", saved.getId())
                .channel(channel)
                .amount(saved.getAmount())
                .attr("promiseValueBand", OperationBands.valueBand(saved.getAmount()))
                .attr("daysToDue", ChronoUnit.DAYS.between(CollectionViews.today(clock), saved.getDueDate()))
                .attr("coverage", saved.getAmount().amount().divide(c.getOpenBalance().amount(), 4,
                        java.math.RoundingMode.HALF_EVEN))
                .attr("operatorId", saved.getOperatorId()));
        publish(saved, null);
        return saved;
    }

    PaymentPromise changed(PaymentPromise p, PromiseStatus previous, String reason) {
        PaymentPromise saved = promises.saveAndFlush(p);
        if (previous == saved.getStatus()) {
            return saved;
        }
        auditTrail.record(AuditEntry.of("promise.status_changed", "promise", saved.getId())
                .creditor(saved.getCreditorId())
                .reason(reason)
                .change("status", previous, saved.getStatus())
                .meta("paidAmount", saved.getPaidAmount()));
        String type = switch (saved.getStatus()) {
            case KEPT -> BusinessEventTypes.PROMISE_KEPT;
            case BROKEN -> BusinessEventTypes.PROMISE_BROKEN;
            default -> null;
        };
        if (type != null) {
            Instant now = Instant.now(clock);
            businessEvents.log(BusinessEvent.of(type)
                    .creditor(saved.getCreditorId()).portfolio(saved.getPortfolioId()).debtor(saved.getDebtorId())
                    .collectionCase(saved.getCaseId())
                    .reference("promise", saved.getId())
                    .amount(saved.getAmount())
                    .durationMs(Duration.between(saved.getPromisedAt(), now).toMillis())
                    .attr("paidAmount", saved.getPaidAmount().amount())
                    .attr("promiseValueBand", OperationBands.valueBand(saved.getAmount()))
                    .attr("daysFromDue", ChronoUnit.DAYS.between(saved.getDueDate(), CollectionViews.today(clock)))
                    .attr("partial", saved.getPaidAmount().isPositive() && saved.getStatus() == PromiseStatus.BROKEN)
                    .attr("operatorId", saved.getOperatorId()));
        }
        publish(saved, previous);
        return saved;
    }

    private void publish(PaymentPromise p, PromiseStatus previous) {
        metrics.promise(p.getStatus().name());
        events.publishEvent(new PromiseStatusChanged(Ids.newId(), currentActor.tenantId(), Instant.now(clock), p.getId(),
                p.getCaseId(), p.getDebtorId(), p.getCreditorId(), p.getPortfolioId(), p.getOperatorId(),
                p.getAmount().amount(), p.getPaidAmount().amount(), p.getDueDate(), p.getBreaksAfter(),
                previous == null ? null : previous.name(), p.getStatus().name()));
    }
}
