package br.com.gestorcobranca.shared.events.telephony;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Chamada encerrada, vinculada ao caso (F20).
 *
 * @param direction       OUTBOUND ou INBOUND
 * @param result          ANSWERED (atendida), NO_ANSWER, BUSY, VOICEMAIL, FAILED, CANCELLED
 * @param durationSeconds tempo de conversação (0 quando não atendida)
 * @param portfolioId     carteira do caso, se identificada (segmentação dos indicadores)
 */
public record CallCompleted(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID callId,
        UUID caseId,
        UUID debtorId,
        UUID creditorId,
        UUID operatorId,
        String direction,
        String result,
        int durationSeconds,
        UUID portfolioId) implements IntegrationEvent {

    /** Resultado que caracteriza chamada atendida (entra no TMA e na ocupação). */
    public static final String ANSWERED = "ANSWERED";

    /** Construtor da versão original do contrato (sem carteira). */
    public CallCompleted(UUID eventId, UUID tenantId, Instant occurredAt, UUID callId, UUID caseId, UUID debtorId,
                         UUID creditorId, UUID operatorId, String direction, String result, int durationSeconds) {
        this(eventId, tenantId, occurredAt, callId, caseId, debtorId, creditorId, operatorId, direction, result,
                durationSeconds, null);
    }
}
