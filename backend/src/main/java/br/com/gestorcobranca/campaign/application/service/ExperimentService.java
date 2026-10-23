package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.application.port.in.ExperimentUseCases;
import br.com.gestorcobranca.campaign.application.port.out.ExperimentResultQueries;
import br.com.gestorcobranca.campaign.application.port.out.ExperimentResultQueries.ArmTotals;
import br.com.gestorcobranca.campaign.domain.model.Cadence;
import br.com.gestorcobranca.campaign.domain.model.CadenceDefinition;
import br.com.gestorcobranca.campaign.domain.model.CadenceStatus;
import br.com.gestorcobranca.campaign.domain.model.CadenceStep;
import br.com.gestorcobranca.campaign.domain.model.Campaign;
import br.com.gestorcobranca.campaign.domain.model.CampaignStatus;
import br.com.gestorcobranca.campaign.domain.model.Experiment;
import br.com.gestorcobranca.campaign.domain.model.ExperimentMetric;
import br.com.gestorcobranca.campaign.domain.model.ExperimentVariant;
import br.com.gestorcobranca.campaign.domain.model.StepOverride;
import br.com.gestorcobranca.campaign.domain.repository.CadenceRepository;
import br.com.gestorcobranca.campaign.domain.repository.ExperimentRepository;
import br.com.gestorcobranca.campaign.domain.service.ExperimentStatistics;
import br.com.gestorcobranca.campaign.domain.service.ExperimentStatistics.Interval;
import br.com.gestorcobranca.campaign.domain.service.ExperimentStatistics.Mean;
import br.com.gestorcobranca.campaign.domain.service.ExperimentStatistics.Proportion;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Percentage;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;

@Service
@Transactional
class ExperimentService implements ExperimentUseCases {

    static final BigDecimal DEFAULT_CONTROL_SHARE = BigDecimal.TEN;
    static final int DEFAULT_OBSERVATION_DAYS = 30;
    static final int DEFAULT_MIN_SAMPLE = 100;
    static final int MAX_VARIANTS = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ExperimentRepository experiments;
    private final CadenceRepository cadences;
    private final ExperimentResultQueries results;
    private final CampaignAccess access;
    private final CampaignCodec codec;
    private final CampaignViews views;
    private final TemplateCheck templateCheck;
    private final AuditTrail audit;
    private final Clock clock;

    ExperimentService(ExperimentRepository experiments, CadenceRepository cadences, ExperimentResultQueries results,
                      CampaignAccess access, CampaignCodec codec, CampaignViews views, TemplateCheck templateCheck,
                      AuditTrail audit, Clock clock) {
        this.experiments = experiments;
        this.cadences = cadences;
        this.results = results;
        this.access = access;
        this.codec = codec;
        this.views = views;
        this.templateCheck = templateCheck;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public ExperimentView create(UUID campaignId, ExperimentInput input) {
        Campaign campaign = access.campaignForCommand(campaignId);
        Guard.requireState(campaign.getStatus() != CampaignStatus.FINISHED, "campaign.finished", "Campanha encerrada");
        Guard.requireState(!experiments.existsOpenByCampaign(campaignId), "experiment.already_open",
                "A campanha já tem um teste A/B em rascunho ou em execução");
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        Experiment experiment = experiments.save(new Experiment(campaignId, HexFormat.of().formatHex(salt),
                definition(input)));
        audit.record(AuditEntry.of("experiment.created", "campaign_experiment", experiment.getId())
                .creditor(campaign.getCreditorId()).created("campaignId", campaignId).created("name", experiment.getName())
                .created("controlShare", experiment.getControlShare()).created("variants", experiment.getVariants()));
        return views.experiment(experiment);
    }

    @Override
    public ExperimentView update(UUID experimentId, ExperimentInput input) {
        Experiment experiment = forCommand(experimentId);
        String before = experiment.getVariants();
        experiment.update(definition(input));
        experiments.save(experiment);
        audit.record(AuditEntry.of("experiment.updated", "campaign_experiment", experimentId)
                .change("variants", before, experiment.getVariants()));
        return views.experiment(experiment);
    }

    @Override
    public ExperimentView start(UUID experimentId) {
        Experiment experiment = forCommand(experimentId);
        Campaign campaign = access.visibleCampaign(experiment.getCampaignId());
        Cadence cadence = cadences.findByCampaignIdAndStatus(campaign.getId(), CadenceStatus.PUBLISHED)
                .orElseThrow(() -> new ConflictException("experiment.no_published_cadence",
                        "Publique a régua da campanha antes de iniciar o teste A/B"));
        CadenceDefinition definition = codec.cadence(cadence.getDefinition());
        for (ExperimentVariant variant : codec.variants(experiment.getVariants())) {
            for (StepOverride override : variant.overrides()) {
                CadenceStep step = definition.step(override.stepCode())
                        .orElseThrow(() -> new br.com.gestorcobranca.shared.domain.BusinessRuleException(
                                "experiment.override_step_unknown", "Etapa inexistente na régua publicada: "
                                + override.stepCode()));
                CadenceStep changed = override.applyTo(step);
                templateCheck.check(step.code(), changed.action(), campaign.getCreditorId());
            }
        }
        experiment.start(Instant.now(clock), access.actor().id());
        experiments.save(experiment);
        audit.record(AuditEntry.of("experiment.started", "campaign_experiment", experimentId)
                .creditor(campaign.getCreditorId()).change("status", "DRAFT", "RUNNING")
                .meta("cadenceVersion", cadence.getVersionNumber()));
        return views.experiment(experiment);
    }

    @Override
    public ExperimentView stop(UUID experimentId, String reason) {
        Experiment experiment = forCommand(experimentId);
        experiment.stop(reason, Instant.now(clock), access.actor().id());
        experiments.save(experiment);
        audit.record(AuditEntry.of("experiment.stopped", "campaign_experiment", experimentId)
                .change("status", "RUNNING", "STOPPED").reason(experiment.getStopReason()));
        return views.experiment(experiment);
    }

    @Override
    @Transactional(readOnly = true)
    public ExperimentView get(UUID experimentId) {
        return views.experiment(visible(experimentId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExperimentView> list(UUID campaignId) {
        access.visibleCampaign(campaignId);
        return experiments.findByCampaignId(campaignId).stream().map(views::experiment).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ExperimentResults results(UUID experimentId, boolean matureOnly) {
        Experiment experiment = visible(experimentId);
        Instant now = Instant.now(clock);
        Instant matureBefore = matureOnly ? now.minus(Duration.ofDays(experiment.getObservationDays())) : null;
        Map<String, ArmTotals> totals = new LinkedHashMap<>();
        boolean hasControl = experiment.getControlShare().value().signum() > 0;
        if (hasControl) {
            totals.put(ExperimentVariant.CONTROL, empty(ExperimentVariant.CONTROL, true));
        }
        codec.variants(experiment.getVariants()).forEach(v -> totals.put(v.code(), empty(v.code(), false)));
        results.totals(experimentId, matureBefore, experiment.getObservationDays()).forEach(t -> totals.put(t.arm(), t));
        String baseline = totals.keySet().iterator().next();
        ArmTotals base = totals.get(baseline);
        int minSample = experiment.getMinSamplePerArm();
        List<ArmResult> arms = new ArrayList<>();
        Set<String> warnings = new HashSet<>();
        for (ArmTotals arm : totals.values()) {
            boolean isBaseline = arm.arm().equals(baseline);
            List<MetricResult> metrics = List.of(
                    rate(ExperimentMetric.EFFECTIVE_CONTACT, arm, base, ArmTotals::effectiveContact, isBaseline, minSample),
                    rate(ExperimentMetric.REPLY, arm, base, ArmTotals::replied, isBaseline, minSample),
                    rate(ExperimentMetric.AGREEMENT, arm, base, ArmTotals::agreement, isBaseline, minSample),
                    rate(ExperimentMetric.PAYMENT, arm, base, ArmTotals::paying, isBaseline, minSample),
                    recovered(arm, base, isBaseline, minSample));
            metrics.stream().filter(m -> m.metric().equals(experiment.getPrimaryMetric().name()))
                    .filter(m -> m.warnings().contains(ExperimentStatistics.SAMPLE_BELOW_MINIMUM))
                    .findAny().ifPresent(m -> warnings.add("INSUFFICIENT_SAMPLE"));
            arms.add(new ArmResult(arm.arm(), arm.control(), arm.debtors(), metrics));
        }
        if (totals.size() < 2) {
            warnings.add("NO_COMPARISON");
        }
        return new ExperimentResults(experimentId, experiment.getStatus().name(), experiment.getPrimaryMetric().name(),
                experiment.getObservationDays(), matureOnly, now, baseline, arms, warnings.stream().sorted().toList());
    }

    private static MetricResult rate(ExperimentMetric metric, ArmTotals arm, ArmTotals base,
                                     ToLongFunction<ArmTotals> successes, boolean isBaseline, int minSample) {
        Proportion p = ExperimentStatistics.proportion(successes.applyAsLong(arm), arm.debtors());
        Difference difference = null;
        if (!isBaseline) {
            Proportion b = ExperimentStatistics.proportion(successes.applyAsLong(base), base.debtors());
            difference = difference(ExperimentStatistics.difference(p, b), 4);
        }
        return new MetricResult(metric.name(), p.successes(), p.n(), scale(p.interval().estimate(), 4),
                scale(p.interval().lower(), 4), scale(p.interval().upper(), 4), difference,
                ExperimentStatistics.warnings(p, minSample));
    }

    private static MetricResult recovered(ArmTotals arm, ArmTotals base, boolean isBaseline, int minSample) {
        Mean m = mean(arm);
        Difference difference = isBaseline ? null : difference(ExperimentStatistics.difference(m, mean(base)), 2);
        return new MetricResult(ExperimentMetric.RECOVERED_PER_DEBTOR.name(), null, m.n(),
                scale(m.interval().estimate(), 2), scale(m.interval().lower(), 2), scale(m.interval().upper(), 2),
                difference, ExperimentStatistics.warnings(m, minSample));
    }

    private static Mean mean(ArmTotals t) {
        return ExperimentStatistics.mean(t.debtors(), t.recovered().doubleValue(), t.recoveredSquares().doubleValue());
    }

    private static Difference difference(Interval i, int scale) {
        return new Difference(scale(i.estimate(), scale), scale(i.lower(), scale), scale(i.upper(), scale),
                Double.isFinite(i.lower()) && Double.isFinite(i.upper()) && i.significant());
    }

    private static BigDecimal scale(double value, int scale) {
        return Double.isFinite(value) ? BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_EVEN) : null;
    }

    private static ArmTotals empty(String arm, boolean control) {
        return new ArmTotals(arm, control, 0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private Experiment.Definition definition(ExperimentInput input) {
        Guard.require(input != null, "experiment.required", "Dados do teste A/B obrigatórios");
        List<ExperimentVariant> variants = input.variants() == null ? List.of() : input.variants();
        Guard.require(!variants.isEmpty() && variants.size() <= MAX_VARIANTS, "experiment.variants_invalid",
                "Informe de 1 a %d variantes".formatted(MAX_VARIANTS));
        Set<String> codes = new HashSet<>();
        variants.forEach(v -> Guard.require(v != null && codes.add(v.validate().code()), "experiment.variant_duplicated",
                "Códigos de variante repetidos"));
        BigDecimal control = input.controlShare() == null ? DEFAULT_CONTROL_SHARE : input.controlShare();
        Guard.require(control.signum() >= 0, "experiment.control_share_invalid", "Grupo de controle entre 0% e 50%");
        Guard.require(control.signum() > 0 || variants.size() >= 2, "experiment.nothing_to_compare",
                "Sem grupo de controle, informe ao menos duas variantes");
        ExperimentMetric metric = input.primaryMetric() == null ? ExperimentMetric.PAYMENT
                : CampaignService.parse(ExperimentMetric.class, input.primaryMetric(), "experiment.metric_invalid");
        return new Experiment.Definition(input.name(), input.hypothesis(), metric, Percentage.of(control),
                codec.variants(variants), input.observationDays() == null ? DEFAULT_OBSERVATION_DAYS
                : input.observationDays(), input.minSamplePerArm() == null ? DEFAULT_MIN_SAMPLE : input.minSamplePerArm());
    }

    private Experiment visible(UUID experimentId) {
        Experiment experiment = experiments.findById(experimentId)
                .orElseThrow(() -> ResourceNotFoundException.of("campaign_experiment", experimentId));
        try {
            access.visibleCampaign(experiment.getCampaignId());
        } catch (ResourceNotFoundException e) {
            throw ResourceNotFoundException.of("campaign_experiment", experimentId);
        }
        return experiment;
    }

    private Experiment forCommand(UUID experimentId) {
        Experiment experiment = visible(experimentId);
        access.campaignForCommand(experiment.getCampaignId());
        return experiment;
    }
}
