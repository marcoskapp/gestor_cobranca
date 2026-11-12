package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Agendamentos do módulo (uma instância por vez, via ShedLock), percorrendo as assessorias
 * ativas; cada lote numa transação própria. A falha num tenant é registrada (ERROR + métrica
 * {@code collection.jobs.failures}) e não impede os demais.
 */
@Component
class CollectionJobs {

    private static final Logger LOG = LoggerFactory.getLogger(CollectionJobs.class);

    private final TenantDirectory tenantDirectory;
    private final TenantExecution tenantExecution;
    private final CollectionMaintenance maintenance;
    private final CollectionMetrics metrics;
    private final AutoDistribution autoDistribution;

    CollectionJobs(TenantDirectory tenantDirectory, TenantExecution tenantExecution, CollectionMaintenance maintenance,
                   CollectionMetrics metrics, AutoDistribution autoDistribution) {
        this.tenantDirectory = tenantDirectory;
        this.tenantExecution = tenantExecution;
        this.maintenance = maintenance;
        this.metrics = metrics;
        this.autoDistribution = autoDistribution;
    }

    /**
     * Distribuição automática por fila (distribuicao-por-desempenho.md §4.3): a cada minuto, as filas com horário
     * vencido no fuso de operação, em dias úteis; cada fila numa transação própria — a falha numa fila não impede as
     * demais nem as outras assessorias.
     */
    @Scheduled(cron = "${app.collection.auto-distribution-cron:0 * * * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "collection.auto-distribution", lockAtMostFor = "PT10M", lockAtLeastFor = "PT20S")
    void autoDistribution() {
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            runAutoDistribution(tenantId);
        }
    }

    /** Rodadas automáticas vencidas de uma assessoria; devolve quantas filas foram distribuídas. */
    int runAutoDistribution(UUID tenantId) {
        String job = "collection.auto_distribution";
        List<UUID> due;
        try {
            due = tenantExecution.callInTenant(tenantId, job, autoDistribution::dueQueues);
        } catch (RuntimeException e) {
            metrics.jobFailure(job);
            LOG.atError().addKeyValue("tenantId", tenantId).addKeyValue("job", job).setCause(e)
                    .log("Falha ao listar as filas com distribuição automática vencida");
            return 0;
        }
        int executed = 0;
        for (UUID queueId : due) {
            try {
                if (tenantExecution.callInTenant(tenantId, job, () -> autoDistribution.run(queueId)).isPresent()) {
                    executed++;
                    metrics.autoDistribution("executed");
                }
            } catch (RuntimeException e) {
                metrics.jobFailure(job);
                metrics.autoDistribution("failed");
                LOG.atError().addKeyValue("tenantId", tenantId).addKeyValue("queueId", queueId).addKeyValue("job", job)
                        .setCause(e).log("Falha na distribuição automática da fila");
            }
        }
        return executed;
    }

    /** Retornos que passaram da tolerância (no fuso de operação, pelos instantes gravados em UTC). */
    @Scheduled(fixedDelayString = "${app.collection.callback-check-interval:PT5M}", initialDelayString = "PT2M")
    @SchedulerLock(name = "collection.callback-missed", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    void markMissedCallbacks() {
        forEachTenant("collection.callback_missed", maintenance::markMissedCallbacks);
    }

    /** Diário, logo após a meia-noite no fuso de operação: promessas quebradas e contestações vencidas. */
    @Scheduled(cron = "${app.collection.daily-cron:0 10 0 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "collection.daily", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    void daily() {
        forEachTenant("collection.promise_broken", maintenance::breakPromises);
        forEachTenant("collection.dispute_overdue", maintenance::flagOverdueDisputes);
        forEachTenant("collection.dispute_adjustments", maintenance::reconcileDisputeAdjustments);
        forEachTenant("collection.dispute_links", maintenance::purgeDisputeLinks);
    }

    /**
     * Acompanhamento diário dos pedidos ao credor em horário comercial (padrão 08:05 no fuso de operação): lembrete
     * antes do prazo e cobrança do retorno vencido.
     */
    @Scheduled(cron = "${app.collection.disputes.follow-up-cron:0 5 8 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "collection.dispute-follow-up", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    void disputeFollowUp() {
        forEachTenant("collection.dispute_follow_up", maintenance::followUpCreditorRequests);
    }

    private void forEachTenant(String job, Supplier<Integer> batch) {
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            try {
                int processed;
                do {
                    processed = tenantExecution.callInTenant(tenantId, job, batch);
                } while (processed == CollectionMaintenance.BATCH);
            } catch (RuntimeException e) {
                metrics.jobFailure(job);
                LOG.atError().addKeyValue("tenantId", tenantId).addKeyValue("job", job).setCause(e)
                        .log("Falha em tarefa agendada do módulo collection");
            }
        }
    }
}
