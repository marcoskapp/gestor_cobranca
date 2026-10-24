package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.ActiveCasePage;
import br.com.gestorcobranca.scoring.application.ScoringProperties;
import br.com.gestorcobranca.scoring.application.port.out.ScoringLocks;
import br.com.gestorcobranca.scoring.application.port.out.SnapshotStore;
import br.com.gestorcobranca.scoring.domain.model.CaseScore;
import br.com.gestorcobranca.scoring.domain.model.ScoringModel;
import br.com.gestorcobranca.scoring.domain.model.ScoringRun;
import br.com.gestorcobranca.scoring.domain.model.ScoringSettings;
import br.com.gestorcobranca.scoring.domain.repository.ScoringRunRepository;
import br.com.gestorcobranca.shared.config.TimeConfig;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Escoragem em lote: uma execução por data de referência (agendada ou manual), percorrida em
 * páginas de casos ativos por jobs encadeados ({@code shared.processing_job}, distribuídos entre
 * instâncias com {@code SKIP LOCKED}). Campeão, desafiante e taxas por carteira ficam fixos na
 * execução; em dia de snapshot grava as features para treino e monitoramento.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class ScoreBatchService {

    static final String PAGE_JOB = "scoring.batch_page";
    static final String MONITORING_JOB = "scoring.monitoring";
    private static final Logger LOG = LoggerFactory.getLogger(ScoreBatchService.class);
    private static final int RATE_WINDOW_DAYS = 180;
    private static final int RATE_MIN_ROWS = 30;

    private final ScoringRunRepository runs;
    private final SettingsProvider settingsProvider;
    private final ModelRegistry registry;
    private final CaseScorer scorer;
    private final CaseUseCases cases;
    private final SnapshotStore snapshots;
    private final ScoringLocks locks;
    private final JobQueue jobQueue;
    private final JsonCodec json;
    private final BusinessEventLogger businessEvents;
    private final ScoringMetrics metrics;
    private final ScoringProperties properties;
    private final Clock clock;

    ScoreBatchService(ScoringRunRepository runs, SettingsProvider settingsProvider, ModelRegistry registry,
                      CaseScorer scorer, CaseUseCases cases, SnapshotStore snapshots, ScoringLocks locks,
                      JobQueue jobQueue, JsonCodec json, BusinessEventLogger businessEvents, ScoringMetrics metrics,
                      ScoringProperties properties, Clock clock) {
        this.runs = runs;
        this.settingsProvider = settingsProvider;
        this.registry = registry;
        this.scorer = scorer;
        this.cases = cases;
        this.snapshots = snapshots;
        this.locks = locks;
        this.jobQueue = jobQueue;
        this.json = json;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    record PagePayload(UUID runId) {
    }

    record MonitoringPayload(UUID runId) {
    }

    /**
     * Inicia a escoragem de hoje; devolve a execução do dia se já existir. Vazio quando a base legal
     * não está registrada.
     */
    Optional<ScoringRun> start(ScoringRun.Trigger trigger, UUID requestedBy) {
        ScoringSettings settings = settingsProvider.current();
        if (!settings.legalBasisRegistered()) {
            metrics.batchSkipped("legal_basis_missing");
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.ofInstant(now, TimeConfig.BUSINESS_ZONE);
        locks.lockTenant("batch");
        Optional<ScoringRun> existing = runs.findBatch(today);
        if (existing.isPresent()) {
            metrics.batchSkipped("already_exists");
            return existing;
        }
        ScoringModel champion = registry.ensureChampion(settings);
        ScoringModel challenger = registry.challenger().orElse(null);
        Map<UUID, Double> rates = snapshots.portfolioRates(today.minusDays(RATE_WINDOW_DAYS), today, RATE_MIN_ROWS);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("portfolioRates", CaseScorer.encodeRates(rates));
        ScoringRun run = runs.save(new ScoringRun(ScoringRun.Kind.BATCH, trigger, today, now,
                settings.isSnapshotDay(today), champion.getOutcomeDays(), champion.getId(),
                challenger == null ? null : challenger.getId(), json.toJson(details), requestedBy));
        jobQueue.enqueue(PAGE_JOB, new PagePayload(run.getId()), null, "run:" + run.getId());
        LOG.atInfo().addKeyValue("runId", run.getId()).addKeyValue("referenceDate", today)
                .addKeyValue("snapshot", run.isSnapshot()).addKeyValue("modelId", champion.getId())
                .log("Escoragem em lote iniciada");
        return Optional.of(run);
    }

    /** Uma página da execução; encadeia a próxima ou encerra. */
    void page(UUID runId) {
        long started = System.nanoTime();
        ScoringRun run = runs.findById(runId).orElseThrow(() -> new ResourceNotFoundException("scoring_run", runId));
        if (run.getStatus() != ScoringRun.Status.RUNNING) {
            return;
        }
        ScoringSettings settings = settingsProvider.current();
        ScoringModel champion = registry.find(run.getChampionModelId()).orElseThrow();
        ScoringModel challenger = registry.find(run.getChallengerModelId()).orElse(null);
        ActiveCasePage page = cases.listActiveAfter(run.getCursorCaseId(), properties.batchSize());
        CaseScorer.Result result = scorer.score(page.cases(), new CaseScorer.Context(settings, champion, challenger,
                rates(run), run.getReferenceDate(), run.getReferenceAt(), CaseScore.Trigger.BATCH, run));
        run.pageDone(page.nextAfter(), result.scored(), result.skipped(), result.published(), result.bandChanges(),
                result.failures());
        metrics.batchPage(Duration.ofNanos(System.nanoTime() - started), page.cases().size());
        if (page.nextAfter() != null) {
            runs.save(run);
            jobQueue.enqueue(PAGE_JOB, new PagePayload(run.getId()), null, "run:" + run.getId() + ":" + page.nextAfter());
            return;
        }
        complete(run, champion);
    }

    private void complete(ScoringRun run, ScoringModel champion) {
        Instant now = Instant.now(clock);
        run.finish(ScoringRun.Status.COMPLETED, null, null, now);
        runs.save(run);
        Duration duration = Duration.between(run.getStartedAt(), now);
        metrics.batchFinished("COMPLETED", duration);
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.SCORE_BATCH_COMPLETED)
                .reference("scoring_run", run.getId())
                .durationMs(duration.toMillis())
                .attr("referenceDate", run.getReferenceDate()).attr("trigger", run.getTrigger().name())
                .attr("snapshot", run.isSnapshot()).attr("scored", run.getScored()).attr("skipped", run.getSkipped())
                .attr("published", run.getPublished()).attr("bandChanges", run.getBandChanges())
                .attr("failures", run.getFailures()).attr("modelKind", champion.getKind().name())
                .attr("modelVersion", champion.getModelVersion())
                .attr("challenger", run.getChallengerModelId() != null));
        LOG.atInfo().addKeyValue("runId", run.getId()).addKeyValue("scored", run.getScored())
                .addKeyValue("failures", run.getFailures()).addKeyValue("durationMs", duration.toMillis())
                .log("Escoragem em lote concluída");
        if (run.isSnapshot()) {
            jobQueue.enqueue(MONITORING_JOB, new MonitoringPayload(run.getId()), null, "monitoring:" + run.getId());
        }
    }

    @SuppressWarnings("unchecked")
    Map<UUID, Double> rates(ScoringRun run) {
        Map<String, Object> details = json.toMap(run.getDetails());
        Object encoded = details.get("portfolioRates");
        return CaseScorer.decodeRates(encoded instanceof Map<?, ?> m ? (Map<String, ?>) m : Map.of());
    }

    /** Taxas por carteira para cálculos fora do lote: as da escoragem mais recente (ou calculadas agora). */
    Map<UUID, Double> currentRates() {
        return runs.findLatestBatch().map(this::rates).orElseGet(() -> {
            LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
            return snapshots.portfolioRates(today.minusDays(RATE_WINDOW_DAYS), today, RATE_MIN_ROWS);
        });
    }
}
