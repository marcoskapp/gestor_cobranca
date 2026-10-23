package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.application.CommunicationProperties;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Agendamentos do módulo (uma instância por vez, via ShedLock), percorrendo as assessorias ativas.
 * A falha numa assessoria é registrada (alarme {@code communication.jobs.failures}) e não impede as demais.
 */
@Component
class CommunicationJobs {

    private static final Logger LOG = LoggerFactory.getLogger(CommunicationJobs.class);
    /** Máximo de lotes por assessoria e execução (o restante fica para o dia seguinte). */
    private static final int MAX_BATCHES = 200;

    private final TenantDirectory tenantDirectory;
    private final TenantExecution tenantExecution;
    private final CommunicationMaintenance maintenance;
    private final CommunicationMetrics metrics;
    private final CommunicationProperties properties;

    CommunicationJobs(TenantDirectory tenantDirectory, TenantExecution tenantExecution,
                      CommunicationMaintenance maintenance, CommunicationMetrics metrics,
                      CommunicationProperties properties) {
        this.tenantDirectory = tenantDirectory;
        this.tenantExecution = tenantExecution;
        this.maintenance = maintenance;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Scheduled(cron = "${app.communication.retention-cron:0 40 2 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "communication.retention", lockAtMostFor = "PT4H", lockAtLeastFor = "PT1M")
    void retention() {
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            try {
                int batches = 0;
                int processed;
                do {
                    processed = tenantExecution.callInTenant(tenantId, "communication.retention",
                            maintenance::purgeMessageContent);
                    batches++;
                } while (processed == properties.retentionBatch() && batches < MAX_BATCHES);
                tenantExecution.callInTenant(tenantId, "communication.webhook_receipts", maintenance::purgeWebhookReceipts);
            } catch (RuntimeException e) {
                metrics.jobFailure("retention");
                LOG.atError().addKeyValue("tenantId", tenantId).setCause(e)
                        .log("Falha no descarte de conteúdo de mensagens");
            }
        }
    }
}
