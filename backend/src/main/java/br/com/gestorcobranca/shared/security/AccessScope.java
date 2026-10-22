package br.com.gestorcobranca.shared.security;

import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Aplica o escopo por credor (F02) nos casos de uso. Regras:
 * <ul>
 *   <li>Leitura de um recurso de credor fora do escopo → {@link ResourceNotFoundException}
 *       (não revela que o recurso existe) e registro no log de acesso.</li>
 *   <li>Comando sobre credor fora do escopo → {@link ScopeAccessDeniedException} (HTTP 403)
 *       e registro no log de acesso.</li>
 *   <li>Listagens devem usar {@link #restrictCreditors(Collection)} para filtrar.</li>
 * </ul>
 * O isolamento entre tenants é garantido adicionalmente por RLS no banco.
 */
@Component
public class AccessScope {

    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;

    public AccessScope(CurrentActor currentActor, AccessLogger accessLogger) {
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
    }

    public boolean canAccessCreditor(UUID creditorId) {
        return currentActor.get().creditorScope().includes(creditorId);
    }

    /** Para comandos: nega com 403 se o credor estiver fora do escopo. */
    public void requireCreditor(UUID creditorId) {
        if (!canAccessCreditor(creditorId)) {
            logDenied("creditor", creditorId);
            throw new ScopeAccessDeniedException();
        }
    }

    /** Para leitura por id: nega com 404 se o credor do recurso estiver fora do escopo. */
    public void requireVisible(String resource, UUID resourceId, UUID creditorId) {
        if (!canAccessCreditor(creditorId)) {
            logDenied(resource, resourceId);
            throw new ResourceNotFoundException(resource, resourceId);
        }
    }

    /**
     * Converte o filtro de credores solicitado no conjunto efetivamente permitido.
     * Retorno vazio com {@link #hasAllCreditors()} verdadeiro significa "sem filtro".
     */
    public Set<UUID> restrictCreditors(Collection<UUID> requested) {
        return currentActor.get().creditorScope().restrict(requested);
    }

    public boolean hasAllCreditors() {
        return currentActor.get().creditorScope().all();
    }

    public void requirePermission(Permission permission) {
        Actor actor = currentActor.get();
        if (!actor.has(permission)) {
            accessLogger.log(AccessEvent.of(AccessEventType.ACCESS_DENIED, actor, Map.of("permission", permission.name())));
            throw new ScopeAccessDeniedException();
        }
    }

    private void logDenied(String resource, UUID id) {
        accessLogger.log(AccessEvent.of(AccessEventType.SCOPE_DENIED, currentActor.get(),
                Map.of("resource", resource, "resourceId", String.valueOf(id))));
    }
}
