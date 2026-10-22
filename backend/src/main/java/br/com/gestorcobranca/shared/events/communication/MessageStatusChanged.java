package br.com.gestorcobranca.shared.events.communication;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Mensagem enviada, entregue, lida, respondida ou com falha.
 *
 * @param direction   OUTBOUND ou INBOUND
 * @param status      QUEUED, SENT, DELIVERED, READ, FAILED, RECEIVED
 * @param campaignExecutionId execução de campanha que originou a mensagem, se houver
 * @param caseId      caso associado, se identificado
 */
public record MessageStatusChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID messageId,
        UUID conversationId,
        UUID debtorId,
        UUID creditorId,
        UUID caseId,
        UUID campaignExecutionId,
        String channel,
        String direction,
        String status) implements IntegrationEvent {
}
