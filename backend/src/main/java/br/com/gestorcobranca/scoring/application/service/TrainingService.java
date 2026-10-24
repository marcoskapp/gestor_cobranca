package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.scoring.application.ScoringProperties;
import br.com.gestorcobranca.scoring.application.port.out.SnapshotStore;
import br.com.gestorcobranca.scoring.domain.model.BandCutoffs;
import br.com.gestorcobranca.scoring.domain.model.Feature;
import br.com.gestorcobranca.scoring.domain.model.FeatureVector;
import br.com.gestorcobranca.scoring.domain.model.LogisticDefinition;
import br.com.gestorcobranca.scoring.domain.model.ModelKind;
import br.com.gestorcobranca.scoring.domain.model.ModelStatus;
import br.com.gestorcobranca.scoring.domain.model.ScoringFunction;
import br.com.gestorcobranca.scoring.domain.model.ScoringModel;
import br.com.gestorcobranca.scoring.domain.model.ScoringRun;
import br.com.gestorcobranca.scoring.domain.model.ScoringSettings;
import br.com.gestorcobranca.scoring.domain.model.TrainingRow;
import br.com.gestorcobranca.scoring.domain.repository.ScoringModelRepository;
import br.com.gestorcobranca.scoring.domain.repository.ScoringRunRepository;
import br.com.gestorcobranca.scoring.domain.service.LogisticRegressionTrainer;
import br.com.gestorcobranca.scoring.domain.service.ModelMetrics;
import br.com.gestorcobranca.scoring.domain.service.PopulationStability;
import br.com.gestorcobranca.scoring.domain.service.TrainingPolicy;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Treino da regressão logística no histórico da assessoria (snapshots com desfecho apurado):
 * critério mínimo de dados, divisão temporal, ajuste L2, métricas na validação (AUC, Gini, KS,
 * Brier, calibração por faixa, lift por decil), comparação com o campeão nas mesmas observações e
 * registro do modelo como desafiante (aprovado) ou {@code FAILED_VALIDATION}. Nunca promove.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class TrainingService {

    static final String JOB = "scoring.training";
    private static final Logger LOG = LoggerFactory.getLogger(TrainingService.class);
    private static final int PSI_BINS = 10;

    private final ScoringRunRepository runs;
    private final ScoringModelRepository models;
    private final SnapshotStore snapshots;
    private final ScoringSettingsProvider settingsProvider;
    private final ModelRegistry registry;
    private final JobQueue jobQueue;
    private final JsonCodec json;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final ScoringMetrics metrics;
    private final ScoringProperties properties;
    private final Clock clock;

    TrainingService(ScoringRunRepository runs, ScoringModelRepository models, SnapshotStore snapshots,
                    ScoringSettingsProvider settingsProvider, ModelRegistry registry, JobQueue jobQueue, JsonCodec json,
                    AuditTrail auditTrail, BusinessEventLogger businessEvents, ScoringMetrics metrics,
                    ScoringProperties properties, Clock clock) {
        this.runs = runs;
        this.models = models;
        this.snapshots = snapshots;
        this.settingsProvider = settingsProvider;
        this.registry = registry;
        this.jobQueue = jobQueue;
        this.json = json;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    record Payload(UUID runId) {
    }

    /** Registra o pedido de treino e enfileira o job (um treino por vez na assessoria). */
    ScoringRun request(ScoringRun.Trigger trigger, UUID requestedBy) {
        ScoringSettings settings = settingsProvider.current();
        if (!settings.legalBasisRegistered()) {
            throw new ConflictException("scoring.legal_basis_missing",
                    "Score desabilitado: registre a base legal do tratamento (configurações do score)");
        }
        registry.lockModels();
        if (runs.existsRunning(ScoringRun.Kind.TRAINING)) {
            throw new ConflictException("scoring.training_running", "Já há um treino em andamento");
        }
        Instant now = Instant.now(clock);
        ScoringModel champion = registry.ensureChampion(settings);
        ScoringRun run = runs.save(new ScoringRun(ScoringRun.Kind.TRAINING, trigger,
                LocalDate.ofInstant(now, TimeConfig.BUSINESS_ZONE), now, false, settings.getOutcomeDays(),
                champion.getId(), registry.challenger().map(ScoringModel::getId).orElse(null), null, requestedBy));
        jobQueue.enqueue(JOB, new Payload(run.getId()), null, "training:" + run.getId());
        return run;
    }

    /** Treino agendado: só com base legal, treino automático habilitado e nenhum treino em andamento. */
    java.util.Optional<ScoringRun> requestScheduled() {
        ScoringSettings settings = settingsProvider.current();
        if (!settings.legalBasisRegistered() || !settings.isAutoTrain()) {
            return java.util.Optional.empty();
        }
        registry.lockModels();
        if (runs.existsRunning(ScoringRun.Kind.TRAINING)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(request(ScoringRun.Trigger.SCHEDULED, null));
    }

    /** Executa o treino pedido. */
    void run(UUID runId) {
        long started = System.nanoTime();
        ScoringRun run = runs.findById(runId).orElseThrow(() -> new ResourceNotFoundException("scoring_run", runId));
        if (run.getStatus() != ScoringRun.Status.RUNNING) {
            return;
        }
        ScoringSettings settings = settingsProvider.current();
        LocalDate today = run.getReferenceDate();
        List<TrainingRow> rows = snapshots.trainingRows(today.minusDays(settings.getTrainingWindowDays()), today,
                properties.maxTrainingRows());
        ScoringSettings.Parameters p = settings.parameters();
        TrainingPolicy.Split split = TrainingPolicy.split(rows, new TrainingPolicy.Criteria(p.minCases(),
                p.minPositives(), p.minPartitionPositives(), p.validationShare().doubleValue(), p.outcomeDays()));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("observations", rows.size());
        details.put("distinctCases", split.distinctCases());
        details.put("distinctPositiveCases", split.distinctPositiveCases());
        details.put("minCases", p.minCases());
        details.put("minPositives", p.minPositives());
        if (!split.sufficient()) {
            details.put("reasons", split.reasons());
            finish(run, ScoringRun.Status.INSUFFICIENT_DATA, details, null, started, "insufficient_data");
            LOG.atInfo().addKeyValue("runId", runId).addKeyValue("reasons", split.reasons())
                    .log("Treino não executado: histórico insuficiente (assessoria segue no campeão atual)");
            return;
        }
        LogisticRegressionTrainer.Fit fit;
        try {
            fit = LogisticRegressionTrainer.train(features(split.training()), labels(split.training()),
                    List.of(Feature.values()), settings.getL2Lambda().doubleValue());
        } catch (IllegalArgumentException | ArithmeticException e) {
            details.put("reasons", List.of("training_error"));
            LOG.atError().addKeyValue("runId", runId).setCause(e).log("Falha numérica no treino do modelo de score");
            finish(run, ScoringRun.Status.FAILED, details, null, started, "error");
            return;
        }
        LogisticDefinition definition = fit.definition();
        BandCutoffs bands = settings.bands();
        Map<String, Object> validation = evaluate(definition, split.validation(), bands);
        Map<String, Object> training = evaluate(definition, split.training(), bands);
        ScoringModel champion = registry.find(run.getChampionModelId()).orElseThrow();
        Map<String, Object> championOnValidation = evaluate(registry.function(champion), split.validation(),
                champion.bands());
        Map<String, Object> metricsJson = new LinkedHashMap<>();
        metricsJson.put("validation", validation);
        metricsJson.put("training", training);
        metricsJson.put("championOnValidation", championOnValidation);
        metricsJson.put("championModelId", champion.getId().toString());
        metricsJson.put("iterations", fit.iterations());
        metricsJson.put("converged", fit.converged());
        metricsJson.put("lambda", settings.getL2Lambda());
        Double auc = (Double) validation.get("auc");
        List<String> failures = new ArrayList<>();
        if (!fit.converged()) {
            failures.add("not_converged");
        }
        if (auc == null || auc < settings.getMinAuc().doubleValue()) {
            failures.add("validation_auc_below_minimum");
        }
        ModelStatus status = failures.isEmpty() ? ModelStatus.CHALLENGER : ModelStatus.FAILED_VALIDATION;
        Instant now = Instant.now(clock);
        registry.lockModels();
        if (status == ModelStatus.CHALLENGER) {
            registry.challenger().ifPresent(previous -> {
                previous.supersede(now);
                models.saveAndFlush(previous);
            });
        }
        ScoringModel model = models.save(new ScoringModel(registry.nextVersion(), ModelKind.LOGISTIC_REGRESSION, status,
                "Regressão logística L2 — treino de " + today, settings.getOutcomeDays(), json.toJson(definition), bands,
                paidShare(split.training(), settings.getDefaultPaidShare()),
                new ScoringModel.TrainingSummary(split.trainingFrom(), split.trainingTo(), split.validationFrom(),
                        split.validationTo(), split.training().size(), positives(split.training()),
                        split.validation().size(), positives(split.validation())),
                json.toJson(metricsJson), failures.isEmpty() ? null : String.join(",", failures),
                json.toJson(referenceDistribution(definition, split.training())), run.getRequestedBy(), now));
        auditTrail.record(AuditEntry.of("scoring_model.trained", "scoring_model", model.getId())
                .created("modelVersion", model.getModelVersion()).created("kind", model.getKind())
                .created("status", model.getStatus()).created("validationAuc", auc)
                .created("trainingRows", model.getTrainingRows()).created("validationRows", model.getValidationRows())
                .meta("runId", runId).meta("failures", failures));
        details.put("modelId", model.getId().toString());
        details.put("validationAuc", auc);
        details.put("failures", failures);
        finish(run, ScoringRun.Status.COMPLETED, details, model, started,
                status == ModelStatus.CHALLENGER ? "challenger" : "failed_validation");
    }

    private void finish(ScoringRun run, ScoringRun.Status status, Map<String, Object> details, ScoringModel model,
                        long started, String outcome) {
        Instant now = Instant.now(clock);
        run.finish(status, json.toJson(details), model == null ? null : model.getId(), now);
        runs.save(run);
        Duration duration = Duration.ofNanos(System.nanoTime() - started);
        metrics.training(outcome, duration);
        BusinessEvent event = BusinessEvent.of(BusinessEventTypes.SCORE_TRAINING_COMPLETED)
                .reference("scoring_run", run.getId()).durationMs(duration.toMillis())
                .attr("outcome", outcome).attr("trigger", run.getTrigger().name())
                .attr("observations", details.get("observations"))
                .attr("distinctCases", details.get("distinctCases"))
                .attr("distinctPositiveCases", details.get("distinctPositiveCases"));
        if (model != null) {
            event.attr("modelVersion", model.getModelVersion()).attr("status", model.getStatus().name())
                    .attr("validationAuc", details.get("validationAuc"))
                    .attr("trainingRows", model.getTrainingRows()).attr("validationRows", model.getValidationRows());
        } else {
            event.attr("reasons", String.join(",", castList(details.get("reasons"))));
        }
        businessEvents.log(event);
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object value) {
        return value instanceof List<?> l ? (List<String>) l : List.of();
    }

    /** Métricas de um modelo sobre observações com desfecho. */
    static Map<String, Object> evaluate(ScoringFunction fn, List<TrainingRow> rows, BandCutoffs bands) {
        double[] p = new double[rows.size()];
        int[] y = labels(rows);
        for (int i = 0; i < rows.size(); i++) {
            p[i] = fn.evaluate(rows.get(i).features()).probability();
        }
        Double auc = ModelMetrics.auc(p, y);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", rows.size());
        m.put("positives", positives(rows));
        m.put("auc", round(auc));
        m.put("gini", round(ModelMetrics.gini(auc)));
        m.put("ks", round(ModelMetrics.ks(p, y)));
        m.put("brier", round(ModelMetrics.brier(p, y)));
        m.put("meanPredicted", round(Arrays.stream(p).average().orElse(0)));
        m.put("observedRate", rows.isEmpty() ? null : round((double) positives(rows) / rows.size()));
        m.put("calibration", ModelMetrics.calibration(p, y, bands));
        m.put("liftByDecile", ModelMetrics.liftByDecile(p, y));
        return m;
    }

    /** Distribuição de referência (PSI): score e cada feature na população de treino. */
    static Map<String, Object> referenceDistribution(ScoringFunction fn, List<TrainingRow> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", PopulationStability.reference(rows.stream()
                .map(r -> (double) fn.evaluate(r.features()).score()).toList(), PSI_BINS));
        Map<String, Object> features = new LinkedHashMap<>();
        for (Feature f : Feature.values()) {
            features.put(f.name(), PopulationStability.reference(rows.stream().map(r -> r.features().get(f)).toList(),
                    PSI_BINS));
        }
        out.put("features", features);
        return out;
    }

    /** Fração média do saldo paga pelos casos que pagaram (valor esperado = p × saldo × fração). */
    static BigDecimal paidShare(List<TrainingRow> rows, BigDecimal fallback) {
        double sum = 0;
        int n = 0;
        for (TrainingRow r : rows) {
            if (r.paid() && r.openBalance() != null && r.openBalance().signum() > 0 && r.paidAmount() != null) {
                sum += Math.min(1.0, r.paidAmount().doubleValue() / r.openBalance().doubleValue());
                n++;
            }
        }
        if (n == 0 || sum <= 0) {
            return fallback;
        }
        return BigDecimal.valueOf(Math.max(0.0001, sum / n)).setScale(4, RoundingMode.HALF_EVEN);
    }

    private static List<FeatureVector> features(List<TrainingRow> rows) {
        return rows.stream().map(TrainingRow::features).toList();
    }

    static int[] labels(List<TrainingRow> rows) {
        return rows.stream().mapToInt(r -> r.paid() ? 1 : 0).toArray();
    }

    private static int positives(List<TrainingRow> rows) {
        return (int) rows.stream().filter(TrainingRow::paid).count();
    }

    private static Double round(Double v) {
        return v == null ? null : Math.round(v * 1_000_000d) / 1_000_000d;
    }
}
