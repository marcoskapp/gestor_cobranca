package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.application.AssistantProperties;
import br.com.gestorcobranca.assistant.application.port.out.TurnContentStore;
import br.com.gestorcobranca.assistant.domain.model.AssistantSession;
import br.com.gestorcobranca.assistant.domain.repository.AssistantSessionRepository;
import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Agendamentos do módulo (ShedLock), por assessoria ativa: recuperação de turnos interrompidos e descarte
 * do conteúdo da trilha pela política {@code MESSAGE_CONTENT} do compliance (a mesma do conteúdo das
 * mensagens: a trilha não guarda texto por mais tempo que a conversa).
 */
@Component
class AssistantJobs {

    static final String RETENTION_CATEGORY = "MESSAGE_CONTENT";
    private static final Logger LOG = LoggerFactory.getLogger(AssistantJobs.class);

    private final TenantDirectory tenants;
    private final TenantExecution tenantExecution;
    private final AssistantSessionRepository sessions;
    private final TurnOrchestrator orchestrator;
    private final TurnContentStore content;
    private final RetentionUseCases retention;
    private final AssistantMetrics metrics;
    private final AssistantProperties properties;
    private final Clock clock;

    AssistantJobs(TenantDirectory tenants, TenantExecution tenantExecution, AssistantSessionRepository sessions,
                  TurnOrchestrator orchestrator, TurnContentStore content, RetentionUseCases retention,
                  AssistantMetrics metrics, AssistantProperties properties, Clock clock) {
        this.tenants = tenants;
        this.tenantExecution = tenantExecution;
        this.sessions = sessions;
        this.orchestrator = orchestrator;
        this.content = content;
        this.retention = retention;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT2M", initialDelayString = "PT3M")
    @SchedulerLock(name = "assistant.stale-turns", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    void recoverStaleTurns() {
        for (UUID tenant : tenants.activeTenantIds()) {
            try {
                recoverStale(tenant);
            } catch (RuntimeException e) {
                metrics.jobFailure("stale_turns");
                LOG.atError().addKeyValue("tenantId", tenant).setCause(e).log("Falha na recuperação de turnos do assistente");
            }
        }
    }

    @Scheduled(cron = "${app.assistant.retention-cron:0 55 2 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "assistant.retention", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    void retention() {
        for (UUID tenant : tenants.activeTenantIds()) {
            try {
                purge(tenant);
            } catch (RuntimeException e) {
                metrics.jobFailure("retention");
                LOG.atError().addKeyValue("tenantId", tenant).setCause(e).log("Falha no descarte da trilha do assistente");
            }
        }
    }

    /** Recupera os turnos interrompidos da assessoria. @return turnos recuperados */
    int recoverStale(UUID tenant) {
        Instant before = Instant.now(clock).minus(properties.staleTurnAfter());
        List<UUID> conversations = tenantExecution.callInTenant(tenant, "assistant.stale_turns",
                () -> sessions.findByPendingSinceBefore(before).stream().map(AssistantSession::getConversationId).toList());
        for (UUID conversation : conversations) {
            tenantExecution.inTenant(tenant, "assistant.stale_turns", () -> orchestrator.recoverStale(tenant, conversation));
        }
        return conversations.size();
    }

    /** Descarte do conteúdo vencido da assessoria. @return turnos descartados */
    int purge(UUID tenant) {
        return tenantExecution.callInTenant(tenant, "assistant.retention", () -> {
            var policy = retention.policyFor(RETENTION_CATEGORY).filter(RetentionUseCases.RetentionPolicyView::enabled);
            if (policy.isEmpty()) {
                return 0;
            }
            Instant cutoff = java.time.ZonedDateTime.now(clock.withZone(TimeConfig.BUSINESS_ZONE))
                    .minusMonths(policy.get().retentionMonths()).toInstant();
            int purged = content.purgeOlderThan(cutoff, properties.retentionBatch());
            metrics.purged(purged);
            return purged;
        });
    }
}
