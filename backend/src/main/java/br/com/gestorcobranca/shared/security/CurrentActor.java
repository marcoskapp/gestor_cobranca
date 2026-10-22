package br.com.gestorcobranca.shared.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Acesso ao ator da execução corrente (requisição HTTP, job ou consumidor de evento).
 * Injete este componente em vez de ler o {@code SecurityContextHolder} diretamente.
 */
@Component
public class CurrentActor {

    public Optional<Actor> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof ActorAuthentication actorAuthentication) {
            return Optional.of(actorAuthentication.actor());
        }
        return Optional.empty();
    }

    /** @throws MissingExecutionContextException quando não há ator (erro de programação). */
    public Actor get() {
        return find().orElseThrow(MissingExecutionContextException::new);
    }

    public UUID tenantId() {
        return get().tenantId();
    }

    public UUID actorId() {
        return get().id();
    }

    public Optional<UUID> findTenantId() {
        return find().map(Actor::tenantId);
    }

    public CreditorScope creditorScope() {
        return get().creditorScope();
    }

    /** Lançada quando código tenant-scoped executa fora de um contexto autenticado. */
    public static class MissingExecutionContextException extends IllegalStateException {
        public MissingExecutionContextException() {
            super("Nenhum ator/tenant no contexto de execução. Use TenantExecution para jobs e consumidores.");
        }
    }
}
