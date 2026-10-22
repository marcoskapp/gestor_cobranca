package br.com.gestorcobranca.shared.events.collection;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Contestação aberta ou resolvida (F21). Enquanto {@code blocksCollection} for verdadeiro, os
 * títulos contestados ficam fora de réguas e negociações incompatíveis.
 */
public record DisputeStatusChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID disputeId,
        UUID caseId,
        UUID debtorId,
        UUID creditorId,
        List<UUID> titleIds,
        String status,
        boolean blocksCollection) implements IntegrationEvent {

    public DisputeStatusChanged {
        titleIds = titleIds == null ? List.of() : List.copyOf(titleIds);
    }
}
