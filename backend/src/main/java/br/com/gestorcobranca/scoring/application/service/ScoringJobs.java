package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.scoring.domain.model.ScoringRun;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Agendamentos do módulo (uma instância por vez, via ShedLock), por assessoria ativa; falha num
 * tenant é registrada e não impede os demais. O trabalho pesado roda em jobs da fila
 * ({@code SKIP LOCKED}), distribuídos entre as instâncias.
 */
@Component
class ScoringJobs {

    private static final Logger LOG = LoggerFactory.getLogger(ScoringJobs.class);

    private final TenantDirectory tenants;
    private final TenantExecution tenantExecution;
    private final ScoreBatchService batch;
    private final OutcomeService outcomes;
    private final TrainingService training;
    private final ScoringMetrics metrics;

    ScoringJobs(TenantDirectory tenants, TenantExecution tenantExecution, ScoreBatchService batch,
                OutcomeService outcomes, TrainingService training, ScoringMetrics metrics) {
        this.tenants = tenants;
        this.tenantExecution = tenantExecution;
        this.batch = batch;
        this.outcomes = outcomes;
        this.training = training;
        this.metrics = metrics;
    }

    /** Escoragem diária de todos os casos ativos (madrugada, fuso de operação). */
    @Scheduled(cron = "${app.scoring.daily-cron:0 30 2 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "scoring.daily", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    void daily() {
        forEachTenant("scoring.batch_start", () -> batch.start(ScoringRun.Trigger.SCHEDULED, null).isPresent());
    }

    /** Desfechos, recuperado × esperado, retenção e execuções interrompidas. */
    @Scheduled(cron = "${app.scoring.maintenance-cron:0 15 4 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "scoring.maintenance", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    void maintenance() {
        forEachTenant("scoring.outcomes", outcomes::resolveOutcomes);
        forEachTenant("scoring.cohorts", outcomes::measureCohorts);
        forEachTenant("scoring.retention", outcomes::purge);
        forEachTenant("scoring.stale_runs", outcomes::failStaleRuns);
    }

    /** Treino automático mensal (se habilitado); o modelo só entra em produção por promoção manual. */
    @Scheduled(cron = "${app.scoring.training-cron:0 0 5 1 * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "scoring.training", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    void monthlyTraining() {
        forEachTenant("scoring.training_request", () -> training.requestScheduled().isPresent());
    }

    private void forEachTenant(String process, Supplier<?> action) {
        for (UUID tenantId : tenants.activeTenantIds()) {
            try {
                tenantExecution.callInTenant(tenantId, process, action);
            } catch (RuntimeException e) {
                metrics.jobFailure(process);
                LOG.atError().addKeyValue("tenantId", tenantId).addKeyValue("process", process).setCause(e)
                        .log("Falha em processamento agendado do módulo scoring");
            }
        }
    }
}
