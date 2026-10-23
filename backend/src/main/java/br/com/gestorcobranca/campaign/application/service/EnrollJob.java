package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.application.CampaignProperties;
import br.com.gestorcobranca.campaign.application.port.out.AudienceStore;
import br.com.gestorcobranca.campaign.application.port.out.AudienceStore.Selection;
import br.com.gestorcobranca.campaign.domain.model.AudienceSnapshot;
import br.com.gestorcobranca.campaign.domain.model.Cadence;
import br.com.gestorcobranca.campaign.domain.model.CadenceStatus;
import br.com.gestorcobranca.campaign.domain.model.Campaign;
import br.com.gestorcobranca.campaign.domain.model.Experiment;
import br.com.gestorcobranca.campaign.domain.model.Segment;
import br.com.gestorcobranca.campaign.domain.repository.CadenceRepository;
import br.com.gestorcobranca.campaign.domain.repository.CampaignRepository;
import br.com.gestorcobranca.campaign.domain.repository.ExperimentRepository;
import br.com.gestorcobranca.campaign.domain.repository.SegmentRepository;
import br.com.gestorcobranca.campaign.domain.service.PauseRules;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Job {@code campaign.enroll}: avalia o segmento da campanha ativa sobre o read model de audiência
 * (casos ainda não inscritos, em ordem de caso a partir do cursor) e inscreve um lote; lote cheio
 * enfileira a continuação com o cursor (retomada). Cada candidato é inscrito em transação própria
 * ({@link EnrollmentEngine}); a reexecução é segura (a seleção exclui os já inscritos e a inscrição
 * confere de novo sob lock por devedor).
 */
@Component
class EnrollJob implements JobHandler<EnrollJob.Payload> {

    static final String TYPE = "campaign.enroll";
    private static final Logger LOG = LoggerFactory.getLogger(EnrollJob.class);

    private final CampaignRepository campaigns;
    private final CadenceRepository cadences;
    private final SegmentRepository segments;
    private final ExperimentRepository experiments;
    private final AudienceStore audience;
    private final EnrollmentEngine engine;
    private final CampaignCodec codec;
    private final CampaignMetrics metrics;
    private final JobQueue jobQueue;
    private final CampaignProperties properties;
    private final Clock clock;

    EnrollJob(CampaignRepository campaigns, CadenceRepository cadences, SegmentRepository segments,
              ExperimentRepository experiments, AudienceStore audience, EnrollmentEngine engine, CampaignCodec codec,
              CampaignMetrics metrics, JobQueue jobQueue, CampaignProperties properties, Clock clock) {
        this.campaigns = campaigns;
        this.cadences = cadences;
        this.segments = segments;
        this.experiments = experiments;
        this.audience = audience;
        this.engine = engine;
        this.codec = codec;
        this.metrics = metrics;
        this.jobQueue = jobQueue;
        this.properties = properties;
        this.clock = clock;
    }

    /** @param afterCaseId cursor (nulo = início) */
    record Payload(UUID campaignId, UUID afterCaseId) {
    }

    static String dedupKey(UUID campaignId, UUID afterCaseId) {
        return "campaign:" + campaignId + ":" + (afterCaseId == null ? "start" : afterCaseId);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        Campaign campaign = campaigns.findById(payload.campaignId()).orElse(null);
        if (campaign == null || !campaign.isActive()) {
            return;
        }
        Optional<Cadence> cadence = cadences.findByCampaignIdAndStatus(campaign.getId(), CadenceStatus.PUBLISHED);
        Segment segment = segments.findById(campaign.getSegmentId()).orElseThrow();
        if (cadence.isEmpty() || !segment.isActive()) {
            return;
        }
        Optional<Experiment> experiment = experiments.findRunningByCampaign(campaign.getId());
        Instant now = Instant.now(clock);
        Instant started = now;
        Selection selection = new Selection(codec.criteria(segment.getCriteria()), campaign.getCreditorId(),
                campaign.getPortfolioId(), now.atZone(TimeConfig.BUSINESS_ZONE).toLocalDate(), now);
        int batch = properties.enrollmentBatch();
        List<AudienceSnapshot> candidates = audience.candidates(selection, campaign.getId(),
                campaign.getReenrollAfterDays(), PauseRules.pausesOnAgreement(campaign.getPurpose()),
                payload.afterCaseId(), batch);
        metrics.segmentEvaluation("candidates", Duration.between(started, Instant.now(clock)));
        Map<EnrollmentEngine.Outcome, Long> outcomes = candidates.stream()
                .map(candidate -> enroll(campaign.getId(), cadence.get(), experiment, candidate))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        if (candidates.size() == batch) {
            UUID cursor = candidates.getLast().caseId();
            jobQueue.enqueue(TYPE, new Payload(campaign.getId(), cursor), null, dedupKey(campaign.getId(), cursor));
        }
        if (!candidates.isEmpty()) {
            LOG.atInfo().addKeyValue("campaignId", campaign.getId()).addKeyValue("candidates", candidates.size())
                    .addKeyValue("outcomes", outcomes.toString()).log("Lote de inscrição da campanha processado");
        }
    }

    private EnrollmentEngine.Outcome enroll(UUID campaignId, Cadence cadence, Optional<Experiment> experiment,
                                            AudienceSnapshot candidate) {
        try {
            return engine.enroll(campaignId, cadence, experiment, candidate);
        } catch (DataIntegrityViolationException e) {
            // corrida com outra inscrição do mesmo caso/devedor: a próxima varredura reavalia
            return EnrollmentEngine.Outcome.ALREADY_ENROLLED;
        } catch (RuntimeException e) {
            metrics.jobFailure(TYPE);
            LOG.atWarn().addKeyValue("campaignId", campaignId).addKeyValue("caseId", candidate.caseId()).setCause(e)
                    .log("Falha ao inscrever caso na campanha; segue o lote");
            return EnrollmentEngine.Outcome.BLOCKED;
        }
    }
}
