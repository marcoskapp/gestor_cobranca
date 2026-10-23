package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.CollectionTask;
import br.com.gestorcobranca.collection.domain.model.TaskType;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.collection.CallbackStatusChanged;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Efeitos das mudanças de retorno (tarefa {@code CALLBACK}): {@link CallbackStatusChanged},
 * eventos de negócio {@code callback.scheduled}/{@code callback.kept}/{@code callback.missed} e
 * métricas {@code collection.callbacks}. Tarefas de acompanhamento ({@code FOLLOW_UP}) não geram
 * esses efeitos.
 */
@Component
class CallbackRecorder {

    private final ApplicationEventPublisher events;
    private final BusinessEventLogger businessEvents;
    private final CurrentActor currentActor;
    private final CollectionMetrics metrics;
    private final Clock clock;

    CallbackRecorder(ApplicationEventPublisher events, BusinessEventLogger businessEvents, CurrentActor currentActor,
                     CollectionMetrics metrics, Clock clock) {
        this.events = events;
        this.businessEvents = businessEvents;
        this.currentActor = currentActor;
        this.metrics = metrics;
        this.clock = clock;
    }

    void scheduled(CollectionTask task, CollectionCase c) {
        if (task.getType() != TaskType.CALLBACK) {
            return;
        }
        Instant now = Instant.now(clock);
        businessEvents.log(CaseRecorder.base(BusinessEventTypes.CALLBACK_SCHEDULED, c, CollectionViews.today(clock))
                .reference("task", task.getId())
                .attr("assigneeId", task.getAssigneeUserId())
                .attr("leadHours", Duration.between(now, task.getDueAt()).toHours())
                .attr("dueHour", task.getDueAt().atZone(br.com.gestorcobranca.shared.config.TimeConfig.BUSINESS_ZONE).getHour())
                .attr("originInteractionId", task.getOriginInteractionId()));
        publish(task, c, CallbackStatusChanged.SCHEDULED, null, now);
    }

    /** @param onTime cumprido até o fim da tolerância */
    void completed(CollectionTask task, CollectionCase c, boolean onTime) {
        if (task.getType() != TaskType.CALLBACK) {
            return;
        }
        Instant now = Instant.now(clock);
        if (onTime) {
            businessEvents.log(CaseRecorder.base(BusinessEventTypes.CALLBACK_KEPT, c, CollectionViews.today(clock))
                    .reference("task", task.getId())
                    .durationMs(Math.max(0, Duration.between(task.getDueAt(), now).toMillis()))
                    .attr("assigneeId", task.getAssigneeUserId())
                    .attr("completedBy", task.getCompletedBy())
                    .attr("minutesFromDue", Duration.between(task.getDueAt(), now).toMinutes()));
        }
        publish(task, c, onTime ? CallbackStatusChanged.KEPT : CallbackStatusChanged.COMPLETED_LATE,
                task.getCompletedBy(), now);
    }

    void missed(CollectionTask task, CollectionCase c) {
        if (task.getType() != TaskType.CALLBACK) {
            return;
        }
        Instant now = Instant.now(clock);
        businessEvents.log(CaseRecorder.base(BusinessEventTypes.CALLBACK_MISSED, c, CollectionViews.today(clock))
                .reference("task", task.getId())
                .attr("assigneeId", task.getAssigneeUserId())
                .attr("minutesLate", Duration.between(task.getDueAt(), now).toMinutes()));
        publish(task, c, CallbackStatusChanged.MISSED, null, now);
    }

    void cancelled(CollectionTask task, CollectionCase c) {
        if (task.getType() != TaskType.CALLBACK) {
            return;
        }
        publish(task, c, CallbackStatusChanged.CANCELLED, null, Instant.now(clock));
    }

    private void publish(CollectionTask task, CollectionCase c, String status, java.util.UUID completedBy, Instant now) {
        metrics.callback(status);
        events.publishEvent(new CallbackStatusChanged(Ids.newId(), currentActor.tenantId(), now, task.getId(), c.getId(),
                c.getDebtorId(), c.getCreditorId(), c.getPortfolioId(), task.getAssigneeUserId(), c.getAssigneeTeamId(),
                task.getDueAt(), task.getToleranceUntil(), status, completedBy));
    }
}
