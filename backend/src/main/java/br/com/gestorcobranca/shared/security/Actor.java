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
     * {@code CREDITOR}: pessoa do credor no portal do credor (ADR 0011), com permissões só do conjunto
     * {@link Permission#isCreditorOnly() CREDITOR_ONLY} (nenhuma permissão interna: as leituras do portal são métodos
     * próprios dos casos de uso), escopo de um credor e nunca tratada como processo confiável
     * ({@link #bypassesStaffRules()}).
     * <p>
     * {@code ASSISTANT}: assistente de IA (módulo assistant). Age somente por casos de uso, com
     * permissões mínimas e o perfil de alçada configurado na política do assistente — nunca com
     * as permissões de um usuário (ver docs/tecnico/desenho/assistente.md).
     */
    public enum ActorType { USER, SYSTEM, DEBTOR, INTEGRATION, ASSISTANT, CREDITOR }

    public Actor {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        permissions = permissions == null || permissions.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(permissions));
        creditorScope = creditorScope == null ? CreditorScope.NONE : creditorScope;
        // invariante do portal do credor (ADR 0011): só a pessoa do credor tem CREDITOR_ONLY, e só ela
        if (type == ActorType.CREDITOR) {
            if (!Permission.CREDITOR_ONLY.containsAll(permissions)) {
                throw new IllegalArgumentException("Credor só recebe permissões do portal do credor (CREDITOR_ONLY)");
            }
            if (creditorScope.all() || creditorScope.creditorIds().size() != 1) {
                throw new IllegalArgumentException("Credor enxerga exatamente um credor");
            }
        } else if (permissions.stream().anyMatch(Permission::isCreditorOnly)) {
            throw new IllegalArgumentException("Permissão do portal do credor só para a pessoa do credor");
        }
    }

    /**
     * Ator de sistema com acesso total ao tenant informado (jobs, eventos) — todas as permissões, exceto as do portal
     * do credor: nenhum processo age "como o credor".
     */
    public static Actor system(UUID tenantId, String processName) {
        return new Actor(ActorType.SYSTEM, SYSTEM_ID, tenantId, null, "system:" + processName,
                EnumSet.complementOf(EnumSet.copyOf(Permission.CREDITOR_ONLY)), CreditorScope.ALL);
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
        if (permissions.stream().anyMatch(p -> p.isPlatformOnly() || p.isCreditorOnly())) {
            throw new IllegalArgumentException("Assistente não pode receber permissão de plataforma nem do portal do credor");
        }
        return new Actor(ActorType.ASSISTANT, policyId, tenantId, null, "assistant:" + policyCode, permissions,
                CreditorScope.of(creditors));
    }

    /**
     * Pessoa do credor no portal do credor. As permissões vêm do perfil do credor, resolvidas no servidor, e são só do
     * conjunto {@link Permission#CREDITOR_ONLY}: nunca uma permissão interna ou de plataforma.
     *
     * @param creditorUserId usuário do credor
     * @param sessionId      sessão do portal do credor
     * @param creditorId     o único credor visível (uma conta por credor)
     */
    public static Actor creditor(UUID tenantId, UUID creditorUserId, UUID sessionId, UUID creditorId,
                                 Set<Permission> permissions) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(creditorId, "creditorId");
        return new Actor(ActorType.CREDITOR, creditorUserId, tenantId, sessionId, "creditor:" + creditorUserId,
                permissions, CreditorScope.of(Set.of(creditorId)));
    }

    public boolean isCreditor() {
        return type == ActorType.CREDITOR;
    }

    /**
     * Ator dispensado das regras de pessoa da assessoria (responsável e permissões de pessoa) nos serviços: sistema,
     * assistente, integração e o devedor no portal — cada um restrito por outro mecanismo (escopo do token, política do
     * assistente, {@code DebtorSelfScope}). É o sentido que "não é usuário" sempre teve nesses serviços. A pessoa do
     * credor <b>nunca</b> é dispensada. Lista de permissão por {@code switch} exaustivo: um tipo novo não compila até
     * alguém decidir.
     */
    public boolean bypassesStaffRules() {
        return switch (type) {
            case SYSTEM, ASSISTANT, INTEGRATION, DEBTOR -> true;
            case USER, CREDITOR -> false;
        };
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
