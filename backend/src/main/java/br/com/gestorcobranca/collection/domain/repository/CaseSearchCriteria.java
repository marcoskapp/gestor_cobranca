package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.CaseStatus;
import br.com.gestorcobranca.collection.domain.model.OwnerType;

import java.util.Set;
import java.util.UUID;

/**
 * Filtros de busca de casos (todos opcionais).
 *
 * @param allowedCreditorIds credores do escopo do ator; nulo = sem restrição
 */
public record CaseSearchCriteria(Set<UUID> allowedCreditorIds, UUID creditorId, UUID portfolioId, UUID debtorId,
                                 Set<CaseStatus> statuses, UUID queueId, UUID assigneeUserId, OwnerType ownerType,
                                 Boolean eligible) {
}
