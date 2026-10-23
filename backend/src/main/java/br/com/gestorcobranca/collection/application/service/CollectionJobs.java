package br.com.gestorcobranca.collection.application.service;

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

    CollectionJobs(TenantDirectory tenantDirectory, TenantExecution tenantExecution, CollectionMaintenance maintenance,
                   CollectionMetrics metrics) {
        this.tenantDirectory = tenantDirectory;
        this.tenantExecution = tenantExecution;
        this.maintenance = maintenance;
        this.metrics = metrics;
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
