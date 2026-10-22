package br.com.gestorcobranca.shared.events.collection;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Interação registrada num caso (contato humano, mensagem respondida, ligação).
 *
 * @param channel          PHONE, WHATSAPP, EMAIL, SMS, IN_PERSON, PORTAL
 * @param outcomeCode      código da tabulação configurada
 * @param effectiveContact houve contato com o devedor (CPC)
 * @param operatorId       usuário responsável, ou null quando automática
 */
public record InteractionRecorded(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID interactionId,
        UUID caseId,
        UUID debtorId,
        UUID creditorId,
        String channel,
        String outcomeCode,
        boolean effectiveContact,
        UUID operatorId) implements IntegrationEvent {
}
