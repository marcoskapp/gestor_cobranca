package br.com.gestorcobranca.helpdesk.application.service;

import br.com.gestorcobranca.helpdesk.application.HelpdeskProperties;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Retenção do chat (uma instância por vez, por assessoria ativa): descarta o conteúdo das mensagens mais antigas
 * que {@code app.helpdesk.retention-days} (ficam metadados, consumo e avaliações) e expira os rascunhos de chamado
 * não confirmados.
 */
@Component
class HelpdeskJobs {

    static final int BATCH = 500;
    private static final Logger LOG = LoggerFactory.getLogger(HelpdeskJobs.class);

    private final TenantDirectory tenants;
    private final TenantExecution tenantExecution;
    private final ChatStore chats;
    private final TicketStore tickets;
    private final HelpdeskMetrics metrics;
    private final HelpdeskProperties properties;
    private final Clock clock;

    HelpdeskJobs(TenantDirectory tenants, TenantExecution tenantExecution, ChatStore chats, TicketStore tickets,
                 HelpdeskMetrics metrics, HelpdeskProperties properties, Clock clock) {
        this.tenants = tenants;
        this.tenantExecution = tenantExecution;
        this.chats = chats;
        this.tickets = tickets;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.helpdesk.retention-cron:0 35 3 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "helpdesk.retention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    void retention() {
        for (UUID tenantId : tenants.activeTenantIds()) {
            runFor(tenantId);
        }
    }

    /** Uma assessoria (também usado pelos testes). */
    void runFor(UUID tenantId) {
        Instant before = clock.instant().minus(Duration.ofDays(properties.retentionDays()));
        try {
            long purged = 0;
            int batch;
            do {
                batch = tenantExecution.callInTenant(tenantId, "helpdesk.retention", () -> chats.purgeBefore(before, BATCH));
                purged += batch;
            } while (batch == BATCH);
            metrics.purged(purged);
            int expired = tenantExecution.callInTenant(tenantId, "helpdesk.ticket-expiry", () -> tickets.expire(BATCH));
            if (purged > 0 || expired > 0) {
                LOG.atInfo().addKeyValue("tenantId", tenantId).addKeyValue("purged", purged)
                        .addKeyValue("expiredDrafts", expired).log("Retenção do chat de suporte executada");
            }
        } catch (RuntimeException e) {
            metrics.jobFailure("retention");
            LOG.atError().addKeyValue("tenantId", tenantId).setCause(e).log("Falha na retenção do chat de suporte");
        }
    }
}
