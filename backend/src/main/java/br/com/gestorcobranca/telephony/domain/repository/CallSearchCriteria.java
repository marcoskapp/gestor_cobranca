package br.com.gestorcobranca.telephony.domain.repository;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * @param allowedCreditorIds nulo = sem restrição de credor; vazio = nenhum (chamadas sem credor só
 *                           com {@code includeWithoutCreditor})
 */
public record CallSearchCriteria(Set<UUID> allowedCreditorIds, boolean includeWithoutCreditor, UUID creditorId,
                                 UUID caseId, UUID debtorId, UUID operatorId, UUID campaignId, String status,
                                 String result, String direction, Instant from, Instant to) {
}
