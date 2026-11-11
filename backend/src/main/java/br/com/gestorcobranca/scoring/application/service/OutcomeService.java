package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases;
import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases.AppliedRetention;
import br.com.gestorcobranca.scoring.application.ScoringProperties;
import br.com.gestorcobranca.scoring.application.port.out.FactStore;
import br.com.gestorcobranca.scoring.application.port.out.SnapshotStore;
import br.com.gestorcobranca.scoring.application.port.out.SnapshotStore.CohortAggregate;
import br.com.gestorcobranca.scoring.application.port.out.SnapshotStore.CohortOutcome;
import br.com.gestorcobranca.scoring.domain.model.ScoringModel;
import br.com.gestorcobranca.scoring.domain.model.ScoringRun;
import br.com.gestorcobranca.scoring.domain.repository.ScoringModelRepository;
import br.com.gestorcobranca.scoring.domain.repository.ScoringRunRepository;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.scoring.ExpectedRecoveryMeasured;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manutenção diária: apuração dos desfechos (janela encerrada + carência), recuperado × esperado
 * das safras totalmente apuradas (publicado ao {@code incentive} por
 * {@link ExpectedRecoveryMeasured}), retenção de fatos e snapshots e encerramento de execuções
 * interrompidas.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class OutcomeService {

    private static final Logger LOG = LoggerFactory.getLogger(OutcomeService.class);
    private static final int RESOLVE_CHUNK = 5000;
    private static final int PURGE_CHUNK = 5000;
    private static final int MAX_CHUNKS = 200;
    private static final Duration STALE_RUN = Duration.ofHours(24);

    private final SnapshotStore snapshots;
    private final FactStore facts;
    private final ScoringRunRepository runs;
    private final ScoringModelRepository models;
    private final ApplicationEventPublisher events;
    private final BusinessEventLogger businessEvents;
    private final CurrentActor currentActor;
    private final ScoringMetrics metrics;
    private final ScoringProperties properties;
    private final RetentionUseCases retention;
    private final Clock clock;

    OutcomeService(SnapshotStore snapshots, FactStore facts, ScoringRunRepository runs, ScoringModelRepository models,
                   ApplicationEventPublisher events, BusinessEventLogger businessEvents, CurrentActor currentActor,
                   ScoringMetrics metrics, ScoringProperties properties, RetentionUseCases retention, Clock clock) {
        this.snapshots = snapshots;
        this.facts = facts;
        this.runs = runs;
        this.models = models;
        this.events = events;
        this.businessEvents = businessEvents;
        this.currentActor = currentActor;
        this.metrics = metrics;
        this.properties = properties;
        this.retention = retention;
        this.clock = clock;
    }

    /** @return linhas de snapshot apuradas */
    int resolveOutcomes() {
        Instant now = Instant.now(clock);
        Instant dueBefore = now.minus(Duration.ofDays(properties.outcomeGraceDays()));
        int total = 0;
        for (int i = 0; i < MAX_CHUNKS; i++) {
            int n = snapshots.resolveDue(dueBefore, now, RESOLVE_CHUNK);
            total += n;
            if (n < RESOLVE_CHUNK) {
                break;
            }
        }
        metrics.outcomesResolved(total);
        return total;
    }

    /** Recuperado × esperado das safras totalmente apuradas ainda não medidas. */
    int measureCohorts() {
        int measured = 0;
        Map<UUID, ScoringModel> modelCache = new HashMap<>();
        for (ScoringRun run : runs.findSnapshotBatchesPendingCohort()) {
            if (snapshots.hasUnresolved(run.getId())) {
                continue;
            }
            Instant now = Instant.now(clock);
            List<CohortOutcome> cohorts = new ArrayList<>();
            BigDecimal expectedTotal = BigDecimal.ZERO;
            BigDecimal realizedTotal = BigDecimal.ZERO;
            long casesTotal = 0;
            for (CohortAggregate a : snapshots.cohortAggregates(run.getId())) {
                ScoringModel model = modelCache.computeIfAbsent(a.modelId(), id -> models.findById(id).orElseThrow());
                BigDecimal expected = a.probabilityWeightedBalance().multiply(model.getPaidShare())
                        .setScale(2, RoundingMode.HALF_EVEN);
                BigDecimal realized = a.realizedAmount().setScale(2, RoundingMode.HALF_EVEN);
                CohortOutcome cohort = new CohortOutcome(cohortId(run.getId(), a), run.getId(), run.getReferenceDate(),
                        run.getOutcomeDays(), a.creditorId(), a.portfolioId(), a.assigneeUserId(), model.getId(),
                        model.getModelVersion(), a.cases(), BigDecimal.valueOf(a.expectedPayers())
                        .setScale(6, RoundingMode.HALF_EVEN), a.realizedPayers(), expected, realized, now);
                cohorts.add(cohort);
                expectedTotal = expectedTotal.add(expected);
                realizedTotal = realizedTotal.add(realized);
                casesTotal += a.cases();
            }
            snapshots.saveCohorts(cohorts);
            for (CohortOutcome c : cohorts) {
                events.publishEvent(new ExpectedRecoveryMeasured(Ids.newId(), currentActor.tenantId(), now, c.cohortId(),
                        c.referenceDate(), c.outcomeDays(), c.creditorId(), c.portfolioId(), c.assigneeUserId(),
                        c.cases(), c.expectedPayers(), c.realizedPayers(), c.expectedAmount(), c.realizedAmount(),
                        c.modelId(), c.modelVersion()));
            }
            run.cohortMeasured(now);
            runs.save(run);
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.SCORE_COHORT_MEASURED)
                    .reference("scoring_run", run.getId()).amount(realizedTotal)
                    .attr("referenceDate", run.getReferenceDate()).attr("outcomeDays", run.getOutcomeDays())
                    .attr("cohorts", cohorts.size()).attr("cases", casesTotal)
                    .attr("expectedAmount", expectedTotal).attr("realizedAmount", realizedTotal)
                    .attr("ratio", expectedTotal.signum() == 0 ? null
                            : realizedTotal.divide(expectedTotal, 4, RoundingMode.HALF_EVEN)));
            metrics.cohortsMeasured(cohorts.size());
            measured += cohorts.size();
        }
        return measured;
    }

    /**
     * Retenção de fatos e snapshots apurados, pelos prazos da assessoria (tela de Retenção, categorias
     * {@code SCORING_*} do compliance) ou o padrão do sistema ({@code app.scoring.*-retention-days}).
     */
    int purge() {
        Instant now = Instant.now(clock);
        AppliedRetention factRetention = applied(ScoringRetentionDefaults.CASE_FACTS);
        AppliedRetention snapshotRetention = applied(ScoringRetentionDefaults.FEATURE_SNAPSHOTS);
        Instant factCutoff = factRetention.cutoff(now);
        int facts = 0;
        for (int i = 0; i < MAX_CHUNKS; i++) {
            int n = this.facts.purgeOlderThan(factCutoff, PURGE_CHUNK);
            facts += n;
            if (n < PURGE_CHUNK) {
                break;
            }
        }
        LocalDate limit = LocalDate.ofInstant(snapshotRetention.cutoff(now), TimeConfig.BUSINESS_ZONE);
        int rows = 0;
        for (int i = 0; i < MAX_CHUNKS; i++) {
            int n = snapshots.purgeResolvedBefore(limit, PURGE_CHUNK);
            rows += n;
            if (n < PURGE_CHUNK) {
                break;
            }
        }
        metrics.purged("facts", facts);
        metrics.purged("snapshots", rows);
        if (facts + rows > 0) {
            // origem do prazo: TENANT (configurado pela assessoria) ou SYSTEM_DEFAULT (padrão do sistema)
            LOG.atInfo().addKeyValue("process", "scoring.retention").addKeyValue("facts", facts)
                    .addKeyValue("snapshots", rows)
                    .addKeyValue("factDays", factRetention.amount()).addKeyValue("factSource", factRetention.source())
                    .addKeyValue("snapshotDays", snapshotRetention.amount())
                    .addKeyValue("snapshotSource", snapshotRetention.source())
                    .log("Retenção de fatos e snapshots do score executada");
        }
        return facts + rows;
    }

    /** As categorias do scoring têm padrão do sistema e não podem ser desligadas: sempre há prazo. */
    private AppliedRetention applied(String category) {
        return retention.retentionFor(category)
                .orElseThrow(() -> new IllegalStateException("Retenção sem prazo para a categoria " + category));
    }

    /** Execuções em andamento há mais de 24 h (job esgotou as tentativas) ficam como falhas. */
    int failStaleRuns() {
        Instant now = Instant.now(clock);
        int failed = 0;
        for (ScoringRun run : runs.findRunningStartedBefore(now.minus(STALE_RUN))) {
            run.finish(ScoringRun.Status.FAILED, null, null, now);
            runs.save(run);
            metrics.jobFailure("stale_run");
            LOG.atError().addKeyValue("runId", run.getId()).addKeyValue("kind", run.getKind())
                    .log("Execução do score interrompida sem conclusão; marcada como falha");
            failed++;
        }
        return failed;
    }

    /** Id determinístico da safra × grupo (republicação substitui no consumidor). */
    static UUID cohortId(UUID runId, CohortAggregate a) {
        String key = runId + "|" + a.creditorId() + "|" + a.portfolioId() + "|" + a.assigneeUserId() + "|" + a.modelId();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
