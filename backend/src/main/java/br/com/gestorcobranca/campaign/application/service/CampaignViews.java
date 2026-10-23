package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases.EnrollmentView;
import br.com.gestorcobranca.campaign.application.port.in.CampaignExecutionUseCases.StepExecutionView;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CadenceView;
import br.com.gestorcobranca.campaign.application.port.in.CampaignUseCases.CampaignView;
import br.com.gestorcobranca.campaign.application.port.in.ExperimentUseCases.ExperimentView;
import br.com.gestorcobranca.campaign.application.port.in.SegmentUseCases.SegmentView;
import br.com.gestorcobranca.campaign.domain.model.Cadence;
import br.com.gestorcobranca.campaign.domain.model.Campaign;
import br.com.gestorcobranca.campaign.domain.model.Enrollment;
import br.com.gestorcobranca.campaign.domain.model.Experiment;
import br.com.gestorcobranca.campaign.domain.model.Segment;
import br.com.gestorcobranca.campaign.domain.model.StepExecution;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Conversão de entidades em views (nunca expõe entidades). */
@Component
class CampaignViews {

    private final CampaignCodec codec;

    CampaignViews(CampaignCodec codec) {
        this.codec = codec;
    }

    SegmentView segment(Segment s) {
        return new SegmentView(s.getId(), s.getCreditorId(), s.getCode(), s.getName(), s.getDescription(),
                codec.criteria(s.getCriteria()), s.isActive(), s.getCreatedAt(), s.getUpdatedAt(), version(s.getVersion()));
    }

    CampaignView campaign(Campaign c, Optional<Cadence> published, boolean hasDraft) {
        return new CampaignView(c.getId(), c.getCode(), c.getName(), c.getDescription(), c.getCreditorId(),
                c.getPortfolioId(), c.getSegmentId(), c.getPurpose().name(), c.getStatus().name(), c.getPriority(),
                c.getExclusivityGroup(), c.getReenrollAfterDays(), c.getAttributionWindowDays(), c.getPaymentPauseDays(),
                c.getMaxDeferralHours(), c.isExitWhenOutOfSegment(), published.map(Cadence::getId).orElse(null),
                published.map(Cadence::getVersionNumber).orElse(null), hasDraft, c.getActivatedAt(), c.getPausedAt(),
                c.getPauseNote(), c.getFinishedAt(), c.getCreatedAt(), version(c.getVersion()));
    }

    CadenceView cadence(Cadence c) {
        return new CadenceView(c.getId(), c.getCampaignId(), c.getVersionNumber(), c.getStatus().name(),
                codec.cadence(c.getDefinition()), c.getPublishedAt(), c.getPublishedBy(), c.getRetiredAt(),
                c.getCreatedAt(), version(c.getVersion()));
    }

    EnrollmentView enrollment(Enrollment e) {
        return new EnrollmentView(e.getId(), e.getCampaignId(), e.getCadenceId(), e.getCaseId(), e.getDebtorId(),
                e.getCreditorId(), e.getPortfolioId(), e.getStatus().name(), e.getExperimentId(), e.getVariantCode(),
                e.isControl(), e.getEnrolledAt(), e.getNextStepCode(), e.getNextStepIteration(), e.getNextRunAt(),
                e.getLastStepCode(), e.getLastStepAt(), e.getStepsExecuted(), e.getStepsSkipped(),
                e.getPauseReason() == null ? null : e.getPauseReason().name(), e.getPausedAt(), e.getResumeAt(),
                e.getEndReason() == null ? null : e.getEndReason().name(), e.getEndedAt(), e.getObserveUntil(),
                e.getRepliedAt(), e.getEffectiveContactAt(), e.getLastErrorCode(), e.getLastErrorAt(),
                version(e.getVersion()));
    }

    StepExecutionView step(StepExecution s) {
        return new StepExecutionView(s.getId(), s.getStepCode(), s.getIteration(), s.getActionType().name(),
                s.getChannel(), s.getOutcome().name(), s.getReason(), s.getReasonDetail(), s.getMessageId(),
                s.getMessageStatus(), s.getTaskId(), s.getVariantCode(), s.getExecutedAt());
    }

    ExperimentView experiment(Experiment x) {
        return new ExperimentView(x.getId(), x.getCampaignId(), x.getName(), x.getHypothesis(),
                x.getPrimaryMetric().name(), x.getStatus().name(), x.getControlShare().value(),
                codec.variants(x.getVariants()), x.getObservationDays(), x.getMinSamplePerArm(), x.getStartedAt(),
                x.getStoppedAt(), x.getStopReason(), x.getCreatedAt(), version(x.getVersion()));
    }

    private static long version(Long version) {
        return version == null ? 0 : version;
    }
}
