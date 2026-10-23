package br.com.gestorcobranca.communication.domain.repository;

import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.ConversationStatus;
import br.com.gestorcobranca.communication.domain.model.OwnerType;

import java.util.Set;
import java.util.UUID;

/**
 * Filtros da caixa de entrada; nulos não filtram.
 *
 * @param allowedCreditorIds credores do escopo (nulo = todos); conversas sem credor só aparecem sem restrição
 * @param awaitingReply      somente conversas com mensagem do devedor sem resposta
 */
public record ConversationSearchCriteria(Set<UUID> allowedCreditorIds, UUID creditorId, UUID portfolioId, UUID debtorId,
                                         UUID caseId, ChannelType channel, ConversationStatus status,
                                         Set<OwnerType> ownerTypes, UUID ownerUserId, UUID ownerTeamId,
                                         String requiredProfile, Boolean awaitingReply) {
}
