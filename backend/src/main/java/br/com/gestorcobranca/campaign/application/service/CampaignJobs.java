package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Agendamentos do módulo (uma instância por vez, via ShedLock), percorrendo as assessorias ativas. A
 * falha numa assessoria é registrada (alarme {@code campaign.jobs.failures}) e não impede as demais.
 */
@Component
class CampaignJobs {

    private static final Logger LOG = LoggerFactory.getLogger(CampaignJobs.class);

    private final TenantDirectory tenantDirectory;
    private final TenantExecution tenantExecution;
    private final CampaignMaintenance maintenance;
    private final JobQueue jobQueue;
    private final CampaignMetrics metrics;

    CampaignJobs(TenantDirectory tenantDirectory, TenantExecution tenantExecution, CampaignMaintenance maintenance,
                 JobQueue jobQueue, CampaignMetrics metrics) {
        this.tenantDirectory = tenantDirectory;
        this.tenantExecution = tenantExecution;
        this.maintenance = maintenance;
        this.jobQueue = jobQueue;
        this.metrics = metrics;
    }

    /** Inscrição de novos casos dos segmentos e reagendamento de etapas atrasadas. */
    @Scheduled(fixedDelayString = "${app.campaign.sweep-interval:PT5M}", initialDelayString = "PT1M")
    @SchedulerLock(name = "campaign.sweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    void sweep() {
        forEachTenant("sweep", maintenance::sweep);
    }

    /** Retomadas programadas e fim do horizonte do grupo de controle. */
    @Scheduled(fixedDelayString = "${app.campaign.maintenance-interval:PT5M}", initialDelayString = "PT2M")
    @SchedulerLock(name = "campaign.maintenance", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    void maintenance() {
        forEachTenant("resume", maintenance::resumeDue);
        forEachTenant("holdout", maintenance::finishHoldouts);
    }

    /** Reconciliação diária do read model de audiência com o collection. */
    @Scheduled(cron = "${app.campaign.audience-cron:0 15 3 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "campaign.audience", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    void audience() {
        forEachTenant("audience", () -> {
            jobQueue.enqueue(AudienceRebuildJob.TYPE, new AudienceRebuildJob.Payload(null, 0), null,
                    AudienceRebuildJob.dedupKey(null, 0));
            return 1;
        });
    }

    private void forEachTenant(String job, java.util.function.Supplier<Integer> action) {
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            try {
                tenantExecution.callInTenant(tenantId, "campaign." + job, action);
            } catch (RuntimeException e) {
                metrics.jobFailure(job);
                LOG.atError().addKeyValue("tenantId", tenantId).addKeyValue("job", job).setCause(e)
                        .log("Falha no agendamento do módulo campaign");
            }
        }
    }
}
