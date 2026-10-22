package br.com.gestorcobranca.shared.security;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Quem executa uma operação: um usuário autenticado, um processo do sistema (job, consumidor de
 * evento, webhook) ou o próprio devedor no portal de autonegociação. Todo registro de
 * auditoria, log de negócio e coluna {@code created_by} referencia um ator.
 *
 * @param type        natureza do ator
 * @param id          id do usuário, do devedor (portal) ou {@link #SYSTEM_ID}
 * @param tenantId    tenant em que o ator opera
 * @param sessionId   sessão autenticada (null para sistema)
 * @param name        nome para exibição/auditoria (nunca documento ou contato)
 * @param permissions permissões efetivas
 * @param creditorScope escopo de credores acessíveis
 */
public record Actor(
        ActorType type,
        UUID id,
        UUID tenantId,
        UUID sessionId,
        String name,
        Set<Permission> permissions,
        CreditorScope creditorScope) {

    /** Identificador fixo usado como autor de operações automáticas. */
    public static final UUID SYSTEM_ID = new UUID(0L, 0L);

    public enum ActorType { USER, SYSTEM, DEBTOR, INTEGRATION }

    public Actor {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        permissions = permissions == null || permissions.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(permissions));
        creditorScope = creditorScope == null ? CreditorScope.NONE : creditorScope;
    }

    /** Ator de sistema com acesso total ao tenant informado (jobs, eventos). */
    public static Actor system(UUID tenantId, String processName) {
        return new Actor(ActorType.SYSTEM, SYSTEM_ID, tenantId, null, "system:" + processName,
                EnumSet.allOf(Permission.class), CreditorScope.ALL);
    }

    public boolean has(Permission permission) {
        return permissions.contains(permission);
    }

    public boolean isUser() {
        return type == ActorType.USER;
    }

    public boolean isSystem() {
        return type == ActorType.SYSTEM;
    }
}
