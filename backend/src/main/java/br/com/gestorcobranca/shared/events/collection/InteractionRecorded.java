package br.com.gestorcobranca.shared.events.collection;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Interação registrada num caso (contato humano, mensagem respondida, ligação).
 *
 * @param channel          PHONE, WHATSAPP, EMAIL, SMS, IN_PERSON, PORTAL
 * @param outcomeCode      código da tabulação configurada
 * @param effectiveContact houve contato com a pessoa certa (CPC)
 * @param operatorId       usuário responsável, ou null quando automática
 * @param direction        OUTBOUND (tentativa de saída) ou INBOUND (contato recebido); null em
 *                         produtores da versão original (tratado como não informado: não conta
 *                         como tentativa)
 * @param portfolioId      carteira do caso (segmentação dos indicadores)
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
        UUID operatorId,
        String direction,
        UUID portfolioId) implements IntegrationEvent {

    /** Construtor da versão original do contrato (sem direção e carteira). */
    public InteractionRecorded(UUID eventId, UUID tenantId, Instant occurredAt, UUID interactionId, UUID caseId,
                               UUID debtorId, UUID creditorId, String channel, String outcomeCode,
                               boolean effectiveContact, UUID operatorId) {
        this(eventId, tenantId, occurredAt, interactionId, caseId, debtorId, creditorId, channel, outcomeCode,
                effectiveContact, operatorId, null, null);
    }
}
