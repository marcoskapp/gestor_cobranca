package br.com.gestorcobranca.shared.events.compliance;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Restrição de contato criada ou removida (opt-out, bloqueio jurídico, solicitação do titular).
 *
 * @param channel    canal afetado (WHATSAPP, EMAIL, SMS, PHONE) ou null para todos
 * @param creditorId credor afetado ou null para todos
 * @param restricted true = restrição ativa
 */
public record ContactRestrictionChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID restrictionId,
        UUID debtorId,
        UUID creditorId,
        String channel,
        String restrictionType,
        boolean restricted) implements IntegrationEvent {
}
