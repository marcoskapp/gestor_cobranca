package br.com.gestorcobranca.publicapi.application.service.platform;

import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.shared.tenancy.TenantSwitch;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Regras comuns às operações da equipe da plataforma sobre uma assessoria (faturamento e controle dos tokens):
 * <ul>
 *   <li>permissão da plataforma <strong>e</strong> ator do tenant da plataforma (defesa em profundidade: as permissões
 *       {@link Permission#PLATFORM_ONLY} só existem no perfil {@code PLATFORM_ADMIN} desse tenant; fora dele → 403 e
 *       {@code ACCESS_DENIED reason=platform_tenant_required});</li>
 *   <li>assessoria alvo existente e diferente do tenant da plataforma (404 nos dois casos);</li>
 *   <li>execução <strong>no tenant da assessoria</strong> por {@link TenantSwitch}, na mesma transação e sob RLS — não há
 *       leitura entre tenants sem RLS;</li>
 *   <li>trilha da plataforma (auditoria com a assessoria como entidade) e log de acesso da plataforma
 *       ({@code PLATFORM_TENANT_DATA_VIEWED}).</li>
 * </ul>
 */
@Component
class PlatformAccess {

    private final TenantSwitch tenantSwitch;
    private final TenantAdministration tenantAdministration;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;

    PlatformAccess(TenantSwitch tenantSwitch, TenantAdministration tenantAdministration, AccessScope accessScope,
                   CurrentActor currentActor, AuditTrail auditTrail, AccessLogger accessLogger) {
        this.tenantSwitch = tenantSwitch;
        this.tenantAdministration = tenantAdministration;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
    }

    Actor requirePlatform(Permission permission) {
        accessScope.requirePermission(permission);
        Actor actor = currentActor.get();
        boolean platform = tenantAdministration.findById(actor.tenantId()).map(TenantView::platform).orElse(false);
        if (!platform) {
            accessLogger.log(AccessEvent.of(AccessEventType.ACCESS_DENIED, actor, Map.of(
                    "permission", permission.name(), "reason", "platform_tenant_required")));
            throw new ScopeAccessDeniedException();
        }
        return actor;
    }

    /** Assessoria alvo: existente e diferente do tenant da plataforma (404 nos dois casos). Ativa ou suspensa. */
    TenantView target(UUID tenantId) {
        if (tenantId == null) {
            throw new ResourceNotFoundException("tenant", "null");
        }
        return tenantAdministration.findById(tenantId).filter(t -> !t.platform())
                .orElseThrow(() -> ResourceNotFoundException.of("tenant", tenantId));
    }

    <T> T inTenant(UUID tenantId, String process, Supplier<T> action) {
        return tenantSwitch.callAsSystemOf(tenantId, process, action);
    }

    void viewed(Actor actor, UUID tenantId, String resource, UUID resourceId) {
        Map<String, String> details = new HashMap<>();
        details.put("resource", resource);
        details.put("targetTenantId", tenantId.toString());
        if (resourceId != null) {
            details.put("resourceId", resourceId.toString());
        }
        accessLogger.log(AccessEvent.of(AccessEventType.PLATFORM_TENANT_DATA_VIEWED, actor, details));
    }

    /** Trilha da plataforma: quem alterou, em qual assessoria e por quê. */
    void platformAudit(String action, TenantView tenant, String reason, Map<String, Object> meta) {
        AuditEntry entry = AuditEntry.of(action, "tenant", tenant.id()).reason(reason == null || reason.isBlank()
                ? null : reason.strip());
        meta.forEach(entry::meta);
        auditTrail.record(entry.meta("targetTenantId", tenant.id()));
    }
}
