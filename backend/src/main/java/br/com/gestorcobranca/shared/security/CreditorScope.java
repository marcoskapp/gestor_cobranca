package br.com.gestorcobranca.shared.security;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Escopo de credores que um ator pode acessar dentro do tenant (F02). {@code all = true}
 * significa todos os credores do tenant (ex.: administradores); caso contrário, apenas os
 * credores listados.
 */
public record CreditorScope(boolean all, Set<UUID> creditorIds) {

    public static final CreditorScope ALL = new CreditorScope(true, Set.of());
    public static final CreditorScope NONE = new CreditorScope(false, Set.of());

    public CreditorScope {
        creditorIds = creditorIds == null ? Set.of() : Set.copyOf(creditorIds);
    }

    public static CreditorScope of(Collection<UUID> creditorIds) {
        return new CreditorScope(false, Set.copyOf(creditorIds));
    }

    public boolean includes(UUID creditorId) {
        return all || creditorIds.contains(creditorId);
    }

    /** Restringe um filtro solicitado ao escopo permitido. */
    public Set<UUID> restrict(Collection<UUID> requested) {
        if (requested == null || requested.isEmpty()) {
            return all ? Set.of() : creditorIds;
        }
        if (all) {
            return Set.copyOf(requested);
        }
        return requested.stream().filter(creditorIds::contains).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
