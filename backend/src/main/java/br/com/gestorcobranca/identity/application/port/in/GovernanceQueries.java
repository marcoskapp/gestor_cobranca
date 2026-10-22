package br.com.gestorcobranca.identity.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Consulta de log de acesso e trilha de auditoria (M09), com verificação de integridade. */
public interface GovernanceQueries {

    PageResponse<AccessLogEntry> accessLog(AccessLogFilter filter, PageQuery page);

    PageResponse<AuditLogEntry> auditTrail(AuditFilter filter, PageQuery page);

    IntegrityReport verifyAuditIntegrity();

    record AccessLogFilter(Instant from, Instant to, UUID actorId, String eventType, Boolean success, String clientIp) {
    }

    record AuditFilter(Instant from, Instant to, UUID actorId, String entityType, UUID entityId, String action,
                       UUID creditorId) {
    }

    record AccessLogEntry(UUID id, Instant occurredAt, String eventType, boolean success, String actorType, UUID actorId,
                          String actorName, UUID sessionId, String principal, String clientIp, String userAgent,
                          String requestId, String route, Map<String, Object> details) {
    }

    record AuditLogEntry(long seq, UUID id, Instant occurredAt, String actorType, UUID actorId, String actorName,
                         String action, String entityType, UUID entityId, UUID creditorId, String reason,
                         Map<String, Object> before, Map<String, Object> after, Map<String, Object> metadata,
                         String requestId, String clientIp, boolean sealed) {
    }

    record IntegrityReport(boolean intact, Long firstInvalidSeq, UUID firstInvalidId, String reason, Instant checkedAt) {
    }
}
