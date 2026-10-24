package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.domain.model.AssistantMode;
import br.com.gestorcobranca.assistant.domain.model.AssistantPolicy;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Ator {@code ASSISTANT} de cada turno (docs/tecnico/desenho/assistente.md §2): id = versão da política
 * vigente, escopo restrito ao credor da conversa e permissões <strong>mínimas</strong> das ferramentas
 * habilitadas no modo — nunca as de um usuário. Cada chamada a outro módulo roda em transação própria
 * ({@link TenantExecution#callAs}), de modo que a recusa de um caso de uso não desfaz o que já foi feito
 * no turno (o turno registra o resultado de cada ferramenta).
 */
@Component
class AssistantActors {

    /** Leitura da conversa, rascunhos e simulação (todos os modos). */
    static final Set<Permission> READ = EnumSet.of(Permission.INBOX_VIEW, Permission.MESSAGE_SEND, Permission.CASE_VIEW,
            Permission.DEBTOR_VIEW, Permission.NEGOTIATION_SIMULATE);

    /** Acrescentadas nos modos autônomos (proposta, aceite, formalização, promessa, retorno, opt-out). */
    static final Set<Permission> ACT = EnumSet.of(Permission.NEGOTIATION_PROPOSE, Permission.AGREEMENT_VIEW,
            Permission.AGREEMENT_FORMALIZE, Permission.INTERACTION_REGISTER, Permission.TASK_MANAGE,
            Permission.CONTACT_RESTRICTION_MANAGE);

    private final TenantExecution tenantExecution;

    AssistantActors(TenantExecution tenantExecution) {
        this.tenantExecution = tenantExecution;
    }

    Actor forPolicy(UUID tenantId, AssistantPolicy policy, AssistantMode mode, UUID creditorId) {
        Set<Permission> permissions = EnumSet.copyOf(READ);
        if (mode.autonomous()) {
            permissions.addAll(ACT);
        }
        return Actor.assistant(tenantId, policy.getId(), policy.getCode(), permissions, Set.of(creditorId));
    }

    <T> T as(Actor actor, Supplier<T> action) {
        return tenantExecution.callAs(actor, action);
    }

    <T> T system(UUID tenantId, Supplier<T> action) {
        return tenantExecution.callInTenant(tenantId, "assistant", action);
    }
}
