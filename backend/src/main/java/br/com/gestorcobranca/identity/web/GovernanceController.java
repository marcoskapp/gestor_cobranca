package br.com.gestorcobranca.identity.web;

import br.com.gestorcobranca.identity.application.port.in.GovernanceQueries;
import br.com.gestorcobranca.identity.application.port.in.GovernanceQueries.AccessLogEntry;
import br.com.gestorcobranca.identity.application.port.in.GovernanceQueries.AccessLogFilter;
import br.com.gestorcobranca.identity.application.port.in.GovernanceQueries.AuditFilter;
import br.com.gestorcobranca.identity.application.port.in.GovernanceQueries.AuditLogEntry;
import br.com.gestorcobranca.identity.application.port.in.GovernanceQueries.IntegrityReport;
import br.com.gestorcobranca.shared.web.ApiPaths;
import br.com.gestorcobranca.shared.web.PageQuery;
import br.com.gestorcobranca.shared.web.PageResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Log de acesso e trilha de auditoria (M09). */
@RestController
@RequestMapping(ApiPaths.V1 + "/governance")
class GovernanceController {

    private final GovernanceQueries governance;

    GovernanceController(GovernanceQueries governance) {
        this.governance = governance;
    }

    @GetMapping("/access-log")
    @PreAuthorize("hasAuthority('ACCESS_LOG_VIEW')")
    PageResponse<AccessLogEntry> accessLog(@RequestParam(required = false) Instant from,
                                           @RequestParam(required = false) Instant to,
                                           @RequestParam(required = false) UUID actorId,
                                           @RequestParam(required = false) String eventType,
                                           @RequestParam(required = false) Boolean success,
                                           @RequestParam(required = false) String clientIp,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return governance.accessLog(new AccessLogFilter(from, to, actorId, eventType, success, clientIp),
                PageQuery.of(page, size));
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    PageResponse<AuditLogEntry> audit(@RequestParam(required = false) Instant from,
                                      @RequestParam(required = false) Instant to,
                                      @RequestParam(required = false) UUID actorId,
                                      @RequestParam(required = false) String entityType,
                                      @RequestParam(required = false) UUID entityId,
                                      @RequestParam(required = false) String action,
                                      @RequestParam(required = false) UUID creditorId,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        return governance.auditTrail(new AuditFilter(from, to, actorId, entityType, entityId, action, creditorId),
                PageQuery.of(page, size));
    }

    @PostMapping("/audit/integrity-check")
    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    IntegrityReport verifyIntegrity() {
        return governance.verifyAuditIntegrity();
    }
}
