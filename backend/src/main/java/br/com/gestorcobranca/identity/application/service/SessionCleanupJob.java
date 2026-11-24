package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.CreditorPortalProperties;
import br.com.gestorcobranca.identity.application.port.out.CreditorLoginThrottle;
import br.com.gestorcobranca.identity.domain.repository.CreditorAccountRepository;
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

/** Marca como expiradas as sessões inativas ou vencidas de todos os tenants (usuários e pessoas do credor). */
@Component
class SessionCleanupJob {

    private static final Logger LOG = LoggerFactory.getLogger(SessionCleanupJob.class);

    private final TenantDirectory tenantDirectory;
    private final TenantExecution tenantExecution;
    private final SessionRepository sessions;
    private static final java.time.Duration THROTTLE_RETENTION = java.time.Duration.ofHours(24);

    private final CreditorAccountRepository creditorAccounts;
    private final CreditorLoginThrottle creditorThrottle;
    private final IdentityProperties properties;
    private final CreditorPortalProperties creditorProperties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    SessionCleanupJob(TenantDirectory tenantDirectory, TenantExecution tenantExecution, SessionRepository sessions,
                      CreditorAccountRepository creditorAccounts, CreditorLoginThrottle creditorThrottle,
                      IdentityProperties properties,
                      CreditorPortalProperties creditorProperties,
                      MeterRegistry meterRegistry, Clock clock) {
        this.tenantDirectory = tenantDirectory;
        this.tenantExecution = tenantExecution;
        this.sessions = sessions;
        this.creditorAccounts = creditorAccounts;
        this.creditorThrottle = creditorThrottle;
        this.properties = properties;
        this.creditorProperties = creditorProperties;
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
            try {
                Instant now = Instant.now(clock);
                // portal do credor (ADR 0011): o resolvedor já recusa as vencidas; aqui o estado fica certo e o limite
                // por IP (HMAC) parado há mais de 24 h sem bloqueio vigente sai (retenção — decisão 1403)
                int creditorExpired = tenantExecution.callInTenant(tenantId, "identity.creditor_session_cleanup",
                        () -> creditorAccounts.expireStaleSessions(now.minus(creditorProperties.idleTimeout()), now));
                meterRegistry.counter("identity.creditor_sessions.expired").increment(creditorExpired);
                int purged = tenantExecution.callInTenant(tenantId, "identity.creditor_throttle_cleanup",
                        () -> creditorThrottle.deleteIdleBefore(now.minus(THROTTLE_RETENTION), now, 1000));
                meterRegistry.counter("identity.creditor_login_throttle.purged").increment(purged);
            } catch (RuntimeException e) {
                LOG.error("Falha na limpeza do portal do credor do tenant {}", tenantId, e);
            }
        }
    }
}
