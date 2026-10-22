package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.IdentityProperties;
import br.com.gestorcobranca.identity.domain.repository.SessionRepository;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Marca como expiradas as sessões inativas ou vencidas de todos os tenants. */
@Component
class SessionCleanupJob {

    private static final Logger LOG = LoggerFactory.getLogger(SessionCleanupJob.class);

    private final TenantDirectory tenantDirectory;
    private final TenantExecution tenantExecution;
    private final SessionRepository sessions;
    private final IdentityProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    SessionCleanupJob(TenantDirectory tenantDirectory, TenantExecution tenantExecution, SessionRepository sessions,
                      IdentityProperties properties, MeterRegistry meterRegistry, Clock clock) {
        this.tenantDirectory = tenantDirectory;
        this.tenantExecution = tenantExecution;
        this.sessions = sessions;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M")
    @SchedulerLock(name = "identity.session-cleanup", lockAtMostFor = "PT10M")
    void expireStaleSessions() {
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            try {
                Instant now = Instant.now(clock);
                int expired = tenantExecution.callInTenant(tenantId, "identity.session_cleanup",
                        () -> sessions.expireStale(now.minus(properties.session().idleTimeout()), now));
                meterRegistry.counter("identity.sessions.expired").increment(expired);
            } catch (RuntimeException e) {
                LOG.error("Falha ao expirar sessões do tenant {}", tenantId, e);
            }
        }
    }
}
