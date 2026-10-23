package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.domain.model.Campaign;
import br.com.gestorcobranca.campaign.domain.model.Enrollment;
import br.com.gestorcobranca.campaign.domain.model.StepExecution;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Eventos de negócio do módulo ({@code docs/tecnico/logs-e-metricas.md} §3.6): ids, códigos e enums —
 * nunca nome, documento, contato ou conteúdo de mensagem.
 */
@Component
class CampaignEvents {

    static final String ENROLLMENT_REF = "campaign_enrollment";
    static final String CAMPAIGN_REF = "campaign";

    private final BusinessEventLogger logger;

    CampaignEvents(BusinessEventLogger logger) {
        this.logger = logger;
    }

    void activated(Campaign c, int cadenceVersion, boolean resumed) {
        logger.log(BusinessEvent.of(BusinessEventTypes.CAMPAIGN_ACTIVATED).creditor(c.getCreditorId())
                .portfolio(c.getPortfolioId()).reference(CAMPAIGN_REF, c.getId())
                .attr("scope", "campaign").attr("campaignCode", c.getCode()).attr("purpose", c.getPurpose())
                .attr("cadenceVersion", cadenceVersion).attr("priority", c.getPriority())
                .attr("exclusivityGroup", c.getExclusivityGroup()).attr("segmentId", c.getSegmentId().toString())
                .attr("resumed", resumed));
    }

    void campaignPaused(Campaign c) {
        logger.log(BusinessEvent.of(BusinessEventTypes.CAMPAIGN_PAUSED).creditor(c.getCreditorId())
                .portfolio(c.getPortfolioId()).reference(CAMPAIGN_REF, c.getId())
                .attr("scope", "campaign").attr("campaignCode", c.getCode()).attr("reason", "MANUAL"));
    }

    void campaignFinished(Campaign c, int endedEnrollments) {
        logger.log(BusinessEvent.of(BusinessEventTypes.CAMPAIGN_FINISHED).creditor(c.getCreditorId())
                .portfolio(c.getPortfolioId()).reference(CAMPAIGN_REF, c.getId())
                .attr("campaignCode", c.getCode()).attr("endedEnrollments", endedEnrollments)
                .durationMs(c.getActivatedAt() == null ? 0 : Duration.between(c.getActivatedAt(),
                        c.getFinishedAt()).toMillis()));
    }

    void enrollmentPaused(Enrollment e, String campaignCode, String reason, String trigger) {
        logger.log(enrollmentEvent(BusinessEventTypes.CAMPAIGN_PAUSED, e, campaignCode)
                .attr("scope", "enrollment").attr("reason", reason).attr("trigger", trigger)
                .attr("stepsExecuted", e.getStepsExecuted()).attr("lastStepCode", e.getLastStepCode()));
    }

    void stepExecuted(Enrollment e, String campaignCode, int cadenceVersion, StepExecution s, Instant startedAt,
                      Instant now) {
        logger.log(enrollmentEvent(BusinessEventTypes.CAMPAIGN_STEP_EXECUTED, e, campaignCode).channel(s.getChannel())
                .attr("cadenceVersion", cadenceVersion).attr("stepCode", s.getStepCode())
                .attr("iteration", s.getIteration()).attr("actionType", s.getActionType())
                .attr("messageId", s.getMessageId() == null ? null : s.getMessageId().toString())
                .attr("taskId", s.getTaskId() == null ? null : s.getTaskId().toString())
                .durationMs(Duration.between(startedAt, now).toMillis()));
    }

    void stepSkipped(Enrollment e, String campaignCode, int cadenceVersion, StepExecution s) {
        logger.log(enrollmentEvent(BusinessEventTypes.CAMPAIGN_STEP_SKIPPED, e, campaignCode).channel(s.getChannel())
                .attr("cadenceVersion", cadenceVersion).attr("stepCode", s.getStepCode())
                .attr("iteration", s.getIteration()).attr("actionType", s.getActionType())
                .attr("reason", s.getReason()).attr("reasonDetail", s.getReasonDetail()));
    }

    void replied(Enrollment e, String campaignCode, String transition, String channel) {
        logger.log(enrollmentEvent(BusinessEventTypes.CAMPAIGN_REPLIED, e, campaignCode).channel(channel)
                .attr("transition", transition).attr("lastStepCode", e.getLastStepCode())
                .attr("stepsExecuted", e.getStepsExecuted())
                .durationMs(e.getLastStepAt() == null || e.getRepliedAt() == null ? 0
                        : Math.max(0, Duration.between(e.getLastStepAt(), e.getRepliedAt()).toMillis())));
    }

    void cadenceExhausted(Enrollment e, Campaign c, int cadenceVersion, int messagesSent) {
        logger.log(enrollmentEvent(BusinessEventTypes.CAMPAIGN_CADENCE_EXHAUSTED, e, c.getCode())
                .attr("purpose", c.getPurpose()).attr("cadenceVersion", cadenceVersion)
                .attr("stepsExecuted", e.getStepsExecuted()).attr("stepsSkipped", e.getStepsSkipped())
                .attr("messagesSent", messagesSent)
                .durationMs(Duration.between(e.getEnrolledAt(), e.getEndedAt()).toMillis()));
    }

    void variantAssigned(Enrollment e, String campaignCode, String arm, boolean control, int bucket) {
        logger.log(enrollmentEvent(BusinessEventTypes.EXPERIMENT_VARIANT_ASSIGNED, e, campaignCode)
                .attr("experimentId", e.getExperimentId().toString()).attr("arm", arm).attr("control", control)
                .attr("bucket", bucket));
    }

    private static BusinessEvent enrollmentEvent(String type, Enrollment e, String campaignCode) {
        return BusinessEvent.of(type).creditor(e.getCreditorId()).portfolio(e.getPortfolioId()).debtor(e.getDebtorId())
                .collectionCase(e.getCaseId()).reference(ENROLLMENT_REF, e.getId())
                .attr("campaignId", e.getCampaignId().toString()).attr("campaignCode", campaignCode)
                .attr("variant", e.getVariantCode()).attr("control", e.isControl());
    }
}
