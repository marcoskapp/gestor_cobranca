package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.application.port.out.CampaignLocks;
import br.com.gestorcobranca.campaign.application.port.out.EnrollmentSuppressions;
import br.com.gestorcobranca.campaign.domain.model.AudienceSnapshot;
import br.com.gestorcobranca.campaign.domain.model.Cadence;
import br.com.gestorcobranca.campaign.domain.model.CadenceDefinition;
import br.com.gestorcobranca.campaign.domain.model.Campaign;
import br.com.gestorcobranca.campaign.domain.model.EndReason;
import br.com.gestorcobranca.campaign.domain.model.Enrollment;
import br.com.gestorcobranca.campaign.domain.model.EnrollmentStatus;
import br.com.gestorcobranca.campaign.domain.model.Experiment;
import br.com.gestorcobranca.campaign.domain.model.ExperimentAssignment;
import br.com.gestorcobranca.campaign.domain.repository.CampaignRepository;
import br.com.gestorcobranca.campaign.domain.repository.EnrollmentRepository;
import br.com.gestorcobranca.campaign.domain.repository.ExperimentRepository;
import br.com.gestorcobranca.campaign.domain.service.ConcurrencyPolicy;
import br.com.gestorcobranca.campaign.domain.service.ConcurrencyPolicy.Running;
import br.com.gestorcobranca.campaign.domain.service.PauseRules;
import br.com.gestorcobranca.campaign.domain.service.VariantAllocator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Inscreve um caso numa campanha, com lock consultivo por devedor:
 * <ol>
 *   <li>já inscrito (em andamento, controle, ou encerrado dentro da reinscrição) → nada;</li>
 *   <li>situação que pausaria a régua (jurídico, suspensão, contestação, acordo…) → não inscreve agora;</li>
 *   <li>teste A/B em execução → braço estável do devedor ({@code experiment.variant_assigned} na
 *       primeira atribuição); controle → inscrição {@code HOLDOUT} (nenhuma ação, sem exclusividade);</li>
 *   <li>concorrência no grupo de exclusividade ({@link ConcurrencyPolicy}): supressão registrada ou
 *       substituição das inscrições de prioridade menor ({@code PREEMPTED});</li>
 *   <li>inscrição ativa com a primeira etapa agendada.</li>
 * </ol>
 */
@Component
class EnrollmentEngine {

    enum Outcome { ENROLLED, HOLDOUT, SUPPRESSED, ALREADY_ENROLLED, BLOCKED }

    private final EnrollmentRepository enrollments;
    private final CampaignRepository campaigns;
    private final ExperimentRepository experiments;
    private final CampaignLocks locks;
    private final CampaignCodec codec;
    private final EnrollmentLifecycle lifecycle;
    private final CampaignEvents events;
    private final CampaignMetrics metrics;
    private final EnrollmentSuppressions suppressions;
    private final Clock clock;

    EnrollmentEngine(EnrollmentRepository enrollments, CampaignRepository campaigns, ExperimentRepository experiments,
                     CampaignLocks locks, CampaignCodec codec, EnrollmentLifecycle lifecycle, CampaignEvents events,
                     CampaignMetrics metrics, EnrollmentSuppressions suppressions, Clock clock) {
        this.enrollments = enrollments;
        this.campaigns = campaigns;
        this.experiments = experiments;
        this.locks = locks;
        this.codec = codec;
        this.lifecycle = lifecycle;
        this.events = events;
        this.metrics = metrics;
        this.suppressions = suppressions;
        this.clock = clock;
    }

    /** Inscrição de um candidato em transação própria (falha de um caso não afeta o lote). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome enroll(UUID campaignId, Cadence cadence, Optional<Experiment> experiment, AudienceSnapshot candidate) {
        Campaign campaign = campaigns.findById(campaignId).orElseThrow();
        if (!campaign.isActive()) {
            return Outcome.BLOCKED;
        }
        locks.lockDebtor(candidate.debtorId());
        Instant now = Instant.now(clock);
        Optional<Enrollment> latest = enrollments.findLatestByCampaignAndCase(campaignId, candidate.caseId());
        if (latest.isPresent() && !reenrollable(latest.get(), campaign, now)) {
            metrics.enrollment("already_enrolled");
            return Outcome.ALREADY_ENROLLED;
        }
        if (PauseRules.blocking(candidate, campaign.getPurpose()).isPresent()) {
            metrics.enrollment("skipped_blocked");
            return Outcome.BLOCKED;
        }
        String variant = null;
        boolean control = false;
        int bucket = 0;
        boolean firstAssignment = false;
        if (experiment.isPresent()) {
            Experiment x = experiment.get();
            Optional<ExperimentAssignment> existing = experiments.findAssignment(x.getId(), candidate.debtorId());
            if (existing.isPresent()) {
                variant = existing.get().getArm();
                control = existing.get().isControl();
                bucket = existing.get().getBucket();
            } else {
                VariantAllocator.Arm arm = VariantAllocator.allocate(x.getSalt(), candidate.debtorId(),
                        x.getControlShare().value(), codec.variants(x.getVariants()));
                experiments.saveAssignment(new ExperimentAssignment(x.getId(), candidate.debtorId(), arm.code(),
                        arm.control(), arm.bucket()));
                variant = arm.code();
                control = arm.control();
                bucket = arm.bucket();
                firstAssignment = true;
                metrics.experimentAssignment(control);
            }
        }
        CadenceDefinition definition = codec.cadence(cadence.getDefinition());
        Enrollment.Origin origin = new Enrollment.Origin(campaignId, cadence.getId(), candidate.caseId(),
                candidate.debtorId(), candidate.creditorId(), candidate.portfolioId(),
                control ? null : campaign.getExclusivityGroup(), campaign.getPriority(),
                experiment.map(Experiment::getId).orElse(null), variant);
        if (control) {
            Enrollment holdout = enrollments.saveAndFlush(Enrollment.holdout(origin, now,
                    experiment.get().getObservationDays()));
            if (firstAssignment) {
                events.variantAssigned(holdout, campaign.getCode(), variant, true, bucket);
            }
            metrics.enrollment("holdout");
            return Outcome.HOLDOUT;
        }
        if (campaign.getExclusivityGroup() != null) {
            List<Enrollment> running = enrollments.findRunningInGroupForUpdate(candidate.debtorId(),
                    campaign.getExclusivityGroup());
            ConcurrencyPolicy.Decision decision = ConcurrencyPolicy.decide(campaignId, campaign.getExclusivityGroup(),
                    campaign.getPriority(), running.stream()
                            .map(r -> new Running(r.getId(), r.getCampaignId(), r.getPriority())).toList());
            if (decision instanceof ConcurrencyPolicy.Suppress suppress) {
                suppressions.record(campaignId, candidate.caseId(), suppress.blocking().campaignId(), now);
                metrics.enrollment("suppressed_concurrency");
                return Outcome.SUPPRESSED;
            }
            if (decision instanceof ConcurrencyPolicy.Preempt preempt) {
                for (Running r : preempt.preempted()) {
                    Enrollment other = running.stream().filter(x -> x.getId().equals(r.enrollmentId())).findFirst()
                            .orElseThrow();
                    Campaign otherCampaign = campaigns.findById(other.getCampaignId()).orElseThrow();
                    lifecycle.end(other, otherCampaign, EndReason.PREEMPTED);
                    // grava já o fim: o Hibernate insere antes de atualizar, e a nova inscrição usaria o índice de exclusividade
                    enrollments.saveAndFlush(other);
                    metrics.enrollment("preempted");
                }
            }
        }
        Enrollment enrollment = enrollments.saveAndFlush(Enrollment.start(origin, definition.first(), now));
        if (firstAssignment) {
            events.variantAssigned(enrollment, campaign.getCode(), variant, false, bucket);
        }
        lifecycle.schedule(enrollment);
        metrics.enrollment("enrolled");
        return Outcome.ENROLLED;
    }

    private static boolean reenrollable(Enrollment latest, Campaign campaign, Instant now) {
        if (latest.isRunning() || latest.getStatus() == EnrollmentStatus.HOLDOUT) {
            return false;
        }
        Integer days = campaign.getReenrollAfterDays();
        return days != null && latest.getEndedAt() != null
                && !latest.getEndedAt().isAfter(now.minus(Duration.ofDays(days)));
    }
}
