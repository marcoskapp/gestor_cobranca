package br.com.gestorcobranca.shared.privacy;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Consulta de compartilhamentos de um titular.
 *
 * @param debtorId      devedor (titular)
 * @param creditorScope credores do escopo de quem consulta; nulo = todos os credores
 */
public record DataSharingQuery(UUID debtorId, Set<UUID> creditorScope) {

    public DataSharingQuery {
        Objects.requireNonNull(debtorId, "debtorId");
        creditorScope = creditorScope == null ? null : Set.copyOf(creditorScope);
    }

    /** Escopo vazio: nenhum credor visível, nada a consultar. */
    public boolean emptyScope() {
        return creditorScope != null && creditorScope.isEmpty();
    }
}
