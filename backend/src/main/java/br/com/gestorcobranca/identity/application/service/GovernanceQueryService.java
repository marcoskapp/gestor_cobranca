package br.com.gestorcobranca.identity.application.service;

import br.com.gestorcobranca.identity.application.port.in.GovernanceQueries;
import br.com.gestorcobranca.identity.application.port.out.GovernanceReadRepository;
import br.com.gestorcobranca.shared.audit.AuditIntegrityService;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Consultas de governança. Cada consulta é ela própria registrada no log de acesso (quem
 * consultou trilhas de auditoria também é auditado). O período consultado é limitado a 93 dias
 * por requisição para proteger o banco.
 */
@Service
@Transactional(readOnly = true)
class GovernanceQueryService implements GovernanceQueries {

    private static final Duration MAX_RANGE = Duration.ofDays(93);

    private final GovernanceReadRepository readRepository;
    private final AuditIntegrityService integrityService;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;
    private final Clock clock;

    GovernanceQueryService(GovernanceReadRepository readRepository, AuditIntegrityService integrityService,
                           AccessScope accessScope, CurrentActor currentActor, AccessLogger accessLogger, Clock clock) {
        this.readRepository = readRepository;
        this.integrityService = integrityService;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
        this.clock = clock;
    }

    @Override
    public PageResponse<AccessLogEntry> accessLog(AccessLogFilter filter, PageQuery page) {
        AccessLogFilter bounded = new AccessLogFilter(from(filter.from(), filter.to()), to(filter.to()), filter.actorId(),
                filter.eventType(), filter.success(), filter.clientIp());
        validateRange(bounded.from(), bounded.to());
        accessLogger.log(AccessEvent.of(AccessEventType.DATA_EXPORTED, currentActor.get(), Map.of("dataset", "access_log")));
        return PageResponse.of(readRepository.accessLog(bounded, page.toPageable()));
    }

    @Override
    public PageResponse<AuditLogEntry> auditTrail(AuditFilter filter, PageQuery page) {
        if (filter.creditorId() != null) {
            accessScope.requireCreditor(filter.creditorId());
        }
        AuditFilter bounded = new AuditFilter(from(filter.from(), filter.to()), to(filter.to()), filter.actorId(),
                filter.entityType(), filter.entityId(), filter.action(), filter.creditorId());
        validateRange(bounded.from(), bounded.to());
        accessLogger.log(AccessEvent.of(AccessEventType.DATA_EXPORTED, currentActor.get(), Map.of("dataset", "audit_trail")));
        return PageResponse.of(readRepository.auditTrail(bounded, page.toPageable()));
    }

    @Override
    public IntegrityReport verifyAuditIntegrity() {
        Instant now = Instant.now(clock);
        return integrityService.verifyCurrentTenant()
                .map(v -> new IntegrityReport(false, v.seq(), v.auditEventId(), v.reason(), now))
                .orElseGet(() -> new IntegrityReport(true, null, null, null, now));
    }

    private Instant to(Instant to) {
        return to == null ? Instant.now(clock) : to;
    }

    private Instant from(Instant from, Instant to) {
        return from == null ? to(to).minus(Duration.ofDays(7)) : from;
    }

    private static void validateRange(Instant from, Instant to) {
        if (from.isAfter(to) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new BusinessRuleException("governance.range_invalid", "Período inválido (máximo de 93 dias por consulta)");
        }
    }
}
