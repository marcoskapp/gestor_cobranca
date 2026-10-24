package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.scoring.application.port.out.SnapshotStore;
import br.com.gestorcobranca.scoring.application.port.out.SnapshotStore.OutcomeRow;
import br.com.gestorcobranca.scoring.application.port.out.SnapshotStore.PopulationRow;
import br.com.gestorcobranca.scoring.domain.model.BandCutoffs;
import br.com.gestorcobranca.scoring.domain.model.Feature;
import br.com.gestorcobranca.scoring.domain.model.MonitoringReport;
import br.com.gestorcobranca.scoring.domain.model.ScoringModel;
import br.com.gestorcobranca.scoring.domain.model.ScoringRun;
import br.com.gestorcobranca.scoring.domain.model.ScoringSettings;
import br.com.gestorcobranca.scoring.domain.repository.MonitoringReportRepository;
import br.com.gestorcobranca.scoring.domain.repository.ScoringModelRepository;
import br.com.gestorcobranca.scoring.domain.repository.ScoringRunRepository;
import br.com.gestorcobranca.scoring.domain.service.ModelMetrics;
import br.com.gestorcobranca.scoring.domain.service.PopulationStability;
import br.com.gestorcobranca.scoring.domain.service.PopulationStability.Distribution;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Monitoramento após cada escoragem com snapshot: PSI do score e de cada feature contra a
 * referência do campeão (fixada no treino, ou na primeira população escorada pelo campeão),
 * desempenho realizado nas safras já apuradas (AUC realizada do campeão e do desafiante, taxa de
 * pagamento por faixa, previsto × observado) e alertas de deriva.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class MonitoringService {

    private static final Logger LOG = LoggerFactory.getLogger(MonitoringService.class);
    private static final int PSI_BINS = 10;
    private static final int REALIZED_WINDOW_DAYS = 180;

    private final ScoringRunRepository runs;
    private final ScoringModelRepository models;
    private final MonitoringReportRepository reports;
    private final SnapshotStore snapshots;
    private final SettingsProvider settingsProvider;
    private final JsonCodec json;
    private final BusinessEventLogger businessEvents;
    private final ScoringMetrics metrics;

    MonitoringService(ScoringRunRepository runs, ScoringModelRepository models, MonitoringReportRepository reports,
                      SnapshotStore snapshots, SettingsProvider settingsProvider, JsonCodec json,
                      BusinessEventLogger businessEvents, ScoringMetrics metrics) {
        this.runs = runs;
        this.models = models;
        this.reports = reports;
        this.snapshots = snapshots;
        this.settingsProvider = settingsProvider;
        this.json = json;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
    }

    /** Distribuição de referência gravada no modelo. */
    record Reference(Distribution score, Map<String, Distribution> features) {
    }

    /** Desempenho realizado de uma safra. */
    record Safra(String referenceDate, long cases, long positives, Double auc, Double challengerAuc,
                 long challengerCases, double meanPredicted, double observedRate,
                 List<ModelMetrics.BandCalibration> bands) {
    }

    record Alert(String level, String kind, String subject, double value, double threshold) {
    }

    MonitoringReport evaluate(UUID runId) {
        if (reports.findByRunId(runId).isPresent()) {
            return reports.findByRunId(runId).get();
        }
        ScoringRun run = runs.findById(runId).orElseThrow();
        ScoringSettings settings = settingsProvider.current();
        ScoringModel champion = models.findById(run.getChampionModelId()).orElseThrow();
        List<PopulationRow> population = snapshots.population(runId);
        List<Double> scores = population.stream().map(r -> (double) ModelMetrics.score(r.championProbability())).toList();
        List<Alert> alerts = new ArrayList<>();
        Map<String, Object> featurePsi = new LinkedHashMap<>();
        BigDecimal scorePsi = null;
        double warn = settings.getPsiWarning().doubleValue();
        double alert = settings.getPsiAlert().doubleValue();
        Reference reference = champion.getReferenceDistribution() == null ? null
                : json.fromJson(champion.getReferenceDistribution(), Reference.class);
        if (reference == null) {
            // primeira população do campeão sem referência de treino (scorecard): vira a referência
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("score", PopulationStability.reference(scores, PSI_BINS));
            Map<String, Object> features = new LinkedHashMap<>();
            for (Feature f : Feature.values()) {
                features.put(f.name(), PopulationStability.reference(population.stream()
                        .map(r -> r.features().get(f)).toList(), PSI_BINS));
            }
            ref.put("features", features);
            if (!population.isEmpty() && champion.defineReferenceDistribution(json.toJson(ref))) {
                models.save(champion);
            }
        } else if (!population.isEmpty()) {
            Double psi = PopulationStability.psi(reference.score(), scores);
            if (psi != null) {
                scorePsi = BigDecimal.valueOf(psi).setScale(6, RoundingMode.HALF_EVEN);
                metrics.psi("score", psi);
                check(alerts, "score_psi", "score", psi, warn, alert);
            }
            for (Feature f : Feature.values()) {
                Distribution d = reference.features() == null ? null : reference.features().get(f.name());
                if (d == null) {
                    continue;
                }
                Double value = PopulationStability.psi(d, population.stream().map(r -> r.features().get(f)).toList());
                if (value != null) {
                    featurePsi.put(f.name(), round(value));
                    metrics.psi("feature", value);
                    check(alerts, "feature_psi", f.name(), value, warn, alert);
                }
            }
        }
        List<Safra> realized = realized(run, champion.bands());
        realized.stream().reduce((a, b) -> b).ifPresent(latest -> {
            if (latest.auc() != null && latest.positives() >= settings.parameters().minPartitionPositives()
                    && latest.auc() < settings.getMinAuc().doubleValue()) {
                alerts.add(new Alert("ALERT", "realized_auc", latest.referenceDate(), latest.auc(),
                        settings.getMinAuc().doubleValue()));
            }
        });
        MonitoringReport.Status status = alerts.stream().anyMatch(a -> a.level().equals("ALERT"))
                ? MonitoringReport.Status.ALERT
                : alerts.isEmpty() ? MonitoringReport.Status.STABLE : MonitoringReport.Status.WARNING;
        MonitoringReport report = reports.save(new MonitoringReport(runId, champion.getId(), run.getReferenceDate(),
                status, population.size(), scorePsi, json.toJson(featurePsi), json.toJson(realized), json.toJson(alerts)));
        if (status != MonitoringReport.Status.STABLE) {
            alerts.forEach(a -> metrics.driftAlert(a.level(), a.kind()));
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.SCORE_DRIFT_DETECTED)
                    .reference("scoring_monitoring", report.getId())
                    .attr("status", status.name()).attr("modelVersion", champion.getModelVersion())
                    .attr("modelKind", champion.getKind().name()).attr("referenceDate", run.getReferenceDate())
                    .attr("scorePsi", scorePsi).attr("alerts", alerts.size())
                    .attr("kinds", alerts.stream().map(a -> a.kind() + ":" + a.subject()).distinct()
                            .collect(Collectors.joining(","))));
            LOG.atWarn().addKeyValue("runId", runId).addKeyValue("modelId", champion.getId())
                    .addKeyValue("status", status).addKeyValue("alerts", alerts.size())
                    .log("Deriva detectada no score de propensão");
        }
        return report;
    }

    /** Safras apuradas na janela, em ordem de data. */
    private List<Safra> realized(ScoringRun run, BandCutoffs bands) {
        List<OutcomeRow> rows = snapshots.resolvedOutcomes(run.getReferenceDate().minusDays(REALIZED_WINDOW_DAYS),
                run.getReferenceDate());
        Map<String, List<OutcomeRow>> byDate = rows.stream().collect(Collectors.groupingBy(
                r -> r.referenceDate().toString(), TreeMap::new, Collectors.toList()));
        List<Safra> out = new ArrayList<>();
        byDate.forEach((date, safra) -> {
            double[] p = safra.stream().mapToDouble(OutcomeRow::championProbability).toArray();
            int[] y = safra.stream().mapToInt(r -> r.paid() ? 1 : 0).toArray();
            List<OutcomeRow> shadow = safra.stream().filter(r -> r.challengerProbability() != null).toList();
            Double challengerAuc = shadow.isEmpty() ? null : ModelMetrics.auc(
                    shadow.stream().mapToDouble(r -> Objects.requireNonNull(r.challengerProbability())).toArray(),
                    shadow.stream().mapToInt(r -> r.paid() ? 1 : 0).toArray());
            long positives = java.util.Arrays.stream(y).filter(v -> v == 1).count();
            out.add(new Safra(date, safra.size(), positives, round(ModelMetrics.auc(p, y)), round(challengerAuc),
                    shadow.size(), round(java.util.Arrays.stream(p).average().orElse(0)),
                    round((double) positives / safra.size()), ModelMetrics.calibration(p, y, bands)));
        });
        return out;
    }

    private static void check(List<Alert> alerts, String kind, String subject, double value, double warn, double alert) {
        if (value >= alert) {
            alerts.add(new Alert("ALERT", kind, subject, round(value), alert));
        } else if (value >= warn) {
            alerts.add(new Alert("WARNING", kind, subject, round(value), warn));
        }
    }

    private static Double round(Double v) {
        return v == null ? null : Math.round(v * 1_000_000d) / 1_000_000d;
    }

    private static double round(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }
}
