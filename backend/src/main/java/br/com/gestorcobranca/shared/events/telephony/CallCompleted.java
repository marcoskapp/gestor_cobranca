package br.com.gestorcobranca.shared.events.telephony;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.UUID;

/** Chamada encerrada, vinculada ao caso (F20). */
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
        int durationSeconds) implements IntegrationEvent {
}
