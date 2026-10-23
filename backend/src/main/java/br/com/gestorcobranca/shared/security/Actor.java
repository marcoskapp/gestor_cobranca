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

    /**
     * {@code ASSISTANT}: assistente de IA (módulo assistant). Age somente por casos de uso, com
     * permissões mínimas e o perfil de alçada configurado na política do assistente — nunca com
     * as permissões de um usuário (ver docs/tecnico/desenho/assistente.md).
     */
    public enum ActorType { USER, SYSTEM, DEBTOR, INTEGRATION, ASSISTANT }

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

    /**
     * Assistente de IA operando sob uma política específica.
     *
     * @param policyId    política do assistente vigente (identifica o ator em auditoria e métricas)
     * @param policyCode  código legível da política, usado no nome do ator
     * @param permissions permissões estritamente necessárias às ferramentas habilitadas
     * @param creditors   credores da conversa em atendimento
     */
    public static Actor assistant(UUID tenantId, UUID policyId, String policyCode, Set<Permission> permissions,
                                  Set<UUID> creditors) {
        if (permissions.contains(Permission.TENANT_MANAGE)) {
            throw new IllegalArgumentException("Assistente não pode receber permissão de plataforma");
        }
        return new Actor(ActorType.ASSISTANT, policyId, tenantId, null, "assistant:" + policyCode, permissions,
                CreditorScope.of(creditors));
    }

    public boolean isAssistant() {
        return type == ActorType.ASSISTANT;
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
