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
 * @param contactId        contato do devedor usado (telefone, e-mail…), ou null quando não houve
 *                         contato específico ou em produtores de versões anteriores
 * @param source           origem do registro (MANUAL, TELEPHONY, COMMUNICATION, ASSISTANT, SYSTEM…), ou null em
 *                         produtores de versões anteriores; interações TELEPHONY e COMMUNICATION espelham
 *                         {@code CallCompleted} e {@code MessageStatusChanged} (quem conta tentativas por esses
 *                         eventos não deve contá-las de novo)
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
        UUID portfolioId,
        UUID contactId,
        String source) implements IntegrationEvent {

    /** Construtor da versão anterior do contrato (sem contato e origem). */
    public InteractionRecorded(UUID eventId, UUID tenantId, Instant occurredAt, UUID interactionId, UUID caseId,
                               UUID debtorId, UUID creditorId, String channel, String outcomeCode,
                               boolean effectiveContact, UUID operatorId, String direction, UUID portfolioId) {
        this(eventId, tenantId, occurredAt, interactionId, caseId, debtorId, creditorId, channel, outcomeCode,
                effectiveContact, operatorId, direction, portfolioId, null, null);
    }

    /** Construtor da versão original do contrato (sem direção e carteira). */
    public InteractionRecorded(UUID eventId, UUID tenantId, Instant occurredAt, UUID interactionId, UUID caseId,
                               UUID debtorId, UUID creditorId, String channel, String outcomeCode,
                               boolean effectiveContact, UUID operatorId) {
        this(eventId, tenantId, occurredAt, interactionId, caseId, debtorId, creditorId, channel, outcomeCode,
                effectiveContact, operatorId, null, null, null, null);
    }
}
