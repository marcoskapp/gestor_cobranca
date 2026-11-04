package br.com.gestorcobranca.publicapi.application.service.platform;

import br.com.gestorcobranca.publicapi.domain.model.IntegrationToken;
import br.com.gestorcobranca.publicapi.domain.model.TokenState;
import br.com.gestorcobranca.publicapi.domain.repository.IntegrationTokenRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.publicapi.PlatformTokensControlled;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Registra as retomadas automáticas agendadas pela plataforma (decisão 764). A retomada já vale na autenticação no
 * instante agendado ({@link IntegrationToken#stateAt}), em todas as instâncias e sem depender deste job; ele só grava a
 * mudança de situação, a auditoria da assessoria, o evento de negócio, o log de acesso e o aviso por e-mail
 * ({@link PlatformTokensControlled#AUTO_RESUMED}). Roda a cada {@code app.public-api.token-resume-interval} (padrão
 * 5 min), uma instância por vez (ShedLock), em todas as assessorias (inclusive suspensas), como ator de sistema de cada
 * uma (RLS). Falha numa assessoria não impede as demais.
 */
@Component
class PlatformTokenResumeJob {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformTokenResumeJob.class);
    private static final String PROCESS = "publicapi.platform_token_resume";

    private final TenantDirectory tenantDirectory;
    private final TenantExecution tenantExecution;
    private final IntegrationTokenRepository tokens;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final BusinessEventLogger businessEvents;
    private final ApplicationEventPublisher events;
    private final CurrentActor currentActor;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    PlatformTokenResumeJob(TenantDirectory tenantDirectory, TenantExecution tenantExecution,
                           IntegrationTokenRepository tokens, AuditTrail auditTrail, AccessLogger accessLogger,
                           BusinessEventLogger businessEvents, ApplicationEventPublisher events,
                           CurrentActor currentActor, MeterRegistry meterRegistry, Clock clock) {
        this.tenantDirectory = tenantDirectory;
        this.tenantExecution = tenantExecution;
        this.tokens = tokens;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.businessEvents = businessEvents;
        this.events = events;
        this.currentActor = currentActor;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.public-api.token-resume-interval:PT5M}",
            initialDelayString = "${app.public-api.token-resume-interval:PT5M}")
    @SchedulerLock(name = "publicapi.platform-token-resume", lockAtMostFor = "PT10M")
    void resumeScheduled() {
        resumeAll();
    }

    /** Grava as retomadas vencidas de todas as assessorias (visível ao pacote para testes). */
    int resumeAll() {
        Timer.Sample sample = Timer.start(meterRegistry);
        int resumed = 0;
        for (UUID tenantId : tenantDirectory.allTenantIds()) {
            try {
                Integer done = tenantExecution.callInTenant(tenantId, PROCESS, this::resumeDueInTenant);
                resumed += done == null ? 0 : done;
            } catch (RuntimeException e) {
                meterRegistry.counter("publicapi.tokens.resume_failures").increment();
                LOG.atError().addKeyValue("tenantId", tenantId).setCause(e)
                        .log("Falha ao registrar a retomada automática de tokens de integração");
            }
        }
        sample.stop(Timer.builder("publicapi.tokens.resume.duration").register(meterRegistry));
        if (resumed > 0) {
            LOG.atInfo().addKeyValue("tokens", resumed).log("Retomadas automáticas de tokens registradas");
        }
        return resumed;
    }

    private int resumeDueInTenant() {
        Instant now = Instant.now(clock);
        List<IntegrationToken> due = tokens.findResumeDue(now);
        for (IntegrationToken token : due) {
            Instant pausedAt = token.getPausedAt();
            Instant scheduled = token.getResumeAt();
            token.resume();
            tokens.save(token);
            auditTrail.record(AuditEntry.of("integration_token.resumed", "integration_token", token.getId())
                    .creditor(token.getCreditorId()).change("state", TokenState.PAUSED, TokenState.ACTIVE)
                    .meta("prefix", token.getDisplayPrefix()).meta("byPlatform", true).meta("trigger", "SCHEDULED")
                    .meta("scheduledAt", scheduled));
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.PUBLIC_API_TOKEN_RESUMED)
                    .creditor(token.getCreditorId()).reference("integration_token", token.getId())
                    .attr("byPlatform", true).attr("trigger", "SCHEDULED")
                    .attr("pausedHours", pausedAt == null ? null : Duration.between(pausedAt, scheduled).toHours()));
            accessLogger.log(AccessEvent.of(AccessEventType.INTEGRATION_TOKEN_RESUMED, currentActor.get(), Map.of(
                    "tokenId", token.getId().toString(), "prefix", token.getDisplayPrefix(),
                    "creditorId", token.getCreditorId().toString(), "trigger", "SCHEDULED")));
            meterRegistry.counter("publicapi.tokens.lifecycle", "operation", "resumed_scheduled").increment();
        }
        if (!due.isEmpty()) {
            events.publishEvent(new PlatformTokensControlled(Ids.newId(), currentActor.tenantId(), now,
                    PlatformTokensControlled.AUTO_RESUMED, due.stream().map(IntegrationToken::getId).toList(),
                    due.stream().map(IntegrationToken::getDisplayPrefix).toList(), null));
        }
        return due.size();
    }
}
