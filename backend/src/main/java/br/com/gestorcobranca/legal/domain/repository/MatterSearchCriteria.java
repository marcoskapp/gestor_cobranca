package br.com.gestorcobranca.legal.domain.repository;

import br.com.gestorcobranca.legal.domain.model.LegalStage;
import br.com.gestorcobranca.legal.domain.model.ResponsibleType;

import java.util.Set;
import java.util.UUID;

/**
 * Filtros da listagem de casos jurídicos (todos opcionais).
 *
 * @param creditorScope     credores permitidos ao ator (nulo = todos)
 * @param lawFirmScope      restrição de escritório parceiro do ator (nulo = sem restrição)
 * @param open              somente não finais (true), somente finais (false) ou ambos (nulo)
 */
public record MatterSearchCriteria(Set<UUID> creditorScope, UUID lawFirmScope, UUID creditorId, UUID portfolioId,
                                   UUID debtorId, UUID collectionCaseId, Set<LegalStage> stages,
                                   ResponsibleType responsibleType, UUID responsibleUserId, UUID lawFirmId,
                                   Boolean open) {
}
