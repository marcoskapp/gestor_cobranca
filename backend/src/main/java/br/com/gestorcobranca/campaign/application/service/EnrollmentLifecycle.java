package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.domain.model.Campaign;
import br.com.gestorcobranca.campaign.domain.model.EndReason;
import br.com.gestorcobranca.campaign.domain.model.Enrollment;
import br.com.gestorcobranca.campaign.domain.model.EnrollmentStatus;
import br.com.gestorcobranca.campaign.domain.model.PauseReason;
import br.com.gestorcobranca.campaign.domain.model.StepExecution;
import br.com.gestorcobranca.campaign.domain.repository.EnrollmentRepository;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Transições de inscrição com os efeitos colaterais comuns: agendamento/cancelamento do job de
 * etapa, métricas e eventos de negócio. Sempre dentro da transação de quem chama, com a inscrição já
 * bloqueada ({@code FOR UPDATE}).
 * <p>
 * Chave do job de etapa: inscrição × etapa × iteração × instante agendado — reagendar (adiamento,
 * retomada) cria outro job; jobs obsoletos encontram a inscrição em outro estado e não fazem nada.
 */
@Component
class EnrollmentLifecycle {

    private final EnrollmentRepository enrollments;
    private final JobQueue jobQueue;
    private final CampaignEvents events;
    private final CampaignMetrics metrics;
    private final Clock clock;

    EnrollmentLifecycle(EnrollmentRepository enrollments, JobQueue jobQueue, CampaignEvents events,
                        CampaignMetrics metrics, Clock clock) {
        this.enrollments = enrollments;
        this.jobQueue = jobQueue;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    static String jobKey(Enrollment e) {
        String step = e.getPendingReplyAction() != null ? StepExecution.REPLY_STEP : e.getNextStepCode();
        int iteration = e.getPendingReplyAction() != null ? e.nextIteration() : e.getNextStepIteration();
        return "enrollment:%s:%s:%d:%d".formatted(e.getId(), step, iteration, e.getNextRunAt().toEpochMilli());
    }

    /** Agenda o job da próxima etapa (ou da ação de resposta pendente) para {@code nextRunAt}. */
    void schedule(Enrollment e) {
        if (e.getStatus() != EnrollmentStatus.ACTIVE || e.getNextRunAt() == null
                || (e.getNextStepCode() == null && e.getPendingReplyAction() == null)) {
            return;
        }
        String step = e.getPendingReplyAction() != null ? StepExecution.REPLY_STEP : e.getNextStepCode();
        int iteration = e.getPendingReplyAction() != null ? e.nextIteration() : e.getNextStepIteration();
        jobQueue.enqueue(StepJob.TYPE, new StepJob.Payload(e.getId(), step, iteration), e.getNextRunAt(), jobKey(e));
    }

    void cancelScheduled(Enrollment e) {
        if (e.getNextRunAt() != null && (e.getNextStepCode() != null || e.getPendingReplyAction() != null)) {
            jobQueue.cancelPending(StepJob.TYPE, jobKey(e));
        }
    }

    /** @return {@code true} se a inscrição passou a pausada */
    boolean pause(Enrollment e, Campaign campaign, PauseReason reason, Instant resumeAt, String trigger) {
        if (e.getStatus() == EnrollmentStatus.PAUSED) {
            return false;
        }
        cancelScheduled(e);
        if (!e.pause(reason, Instant.now(clock), resumeAt)) {
            return false;
        }
        enrollments.save(e);
        metrics.pause("enrollment", reason.name());
        events.enrollmentPaused(e, campaign.getCode(), reason.name(), trigger);
        return true;
    }

    void resume(Enrollment e, String trigger) {
        e.resume(Instant.now(clock));
        enrollments.save(e);
        schedule(e);
        metrics.resume(trigger);
    }

    void end(Enrollment e, Campaign campaign, EndReason reason) {
        if (e.getStatus() == EnrollmentStatus.ENDED) {
            return;
        }
        if (e.getStatus() == EnrollmentStatus.ACTIVE) {
            cancelScheduled(e);
        }
        e.end(reason, Instant.now(clock), campaign.getAttributionWindowDays());
        enrollments.save(e);
        metrics.ended(reason.name());
    }
}
