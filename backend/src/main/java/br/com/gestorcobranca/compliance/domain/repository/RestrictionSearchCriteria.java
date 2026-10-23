package br.com.gestorcobranca.compliance.domain.repository;

import br.com.gestorcobranca.compliance.domain.model.ContactChannel;
import br.com.gestorcobranca.compliance.domain.model.RestrictionStatus;
import br.com.gestorcobranca.compliance.domain.model.RestrictionType;

import java.util.Collection;
import java.util.UUID;

/**
 * Filtros de busca de restrições; campos nulos não filtram.
 *
 * @param allowedCreditorIds credores do escopo do ator; {@code null} = sem restrição. Restrições
 *                           válidas para todos os credores (credor nulo) são sempre incluídas.
 */
public record RestrictionSearchCriteria(Collection<UUID> allowedCreditorIds, UUID debtorId,
                                        UUID creditorId, RestrictionType type, RestrictionStatus status,
                                        ContactChannel channel) {
}
