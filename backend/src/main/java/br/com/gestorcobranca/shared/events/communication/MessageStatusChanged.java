package br.com.gestorcobranca.shared.events.communication;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Mensagem enviada, entregue, lida, respondida ou com falha.
 *
 * @param direction           OUTBOUND ou INBOUND
 * @param status              QUEUED, SENT, DELIVERED, READ, FAILED, RECEIVED
 * @param campaignExecutionId execução de campanha que originou a mensagem, se houver
 * @param caseId              caso associado, se identificado
 * @param senderActorType     autor de uma mensagem OUTBOUND: USER, ASSISTANT, CAMPAIGN ou SYSTEM
 *                            (null em INBOUND ou em produtores da versão original)
 * @param senderActorId       usuário/política do assistente que enviou (null quando não se aplica)
 * @param portfolioId         carteira do caso, se identificada
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
        String status,
        String senderActorType,
        UUID senderActorId,
        UUID portfolioId) implements IntegrationEvent {

    /** Construtor da versão original do contrato (sem autor e carteira). */
    public MessageStatusChanged(UUID eventId, UUID tenantId, Instant occurredAt, UUID messageId, UUID conversationId,
                                UUID debtorId, UUID creditorId, UUID caseId, UUID campaignExecutionId, String channel,
                                String direction, String status) {
        this(eventId, tenantId, occurredAt, messageId, conversationId, debtorId, creditorId, caseId,
                campaignExecutionId, channel, direction, status, null, null, null);
    }
}
