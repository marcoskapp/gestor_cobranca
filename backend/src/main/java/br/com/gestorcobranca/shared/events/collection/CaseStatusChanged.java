package br.com.gestorcobranca.shared.events.collection;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Caso de cobrança aberto, suspenso, reaberto ou encerrado.
 *
 * @param previousStatus null quando o caso acaba de ser aberto
 * @param eligible       resultado da elegibilidade (RB02) após a mudança
 */
public record CaseStatusChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID caseId,
        UUID debtorId,
        UUID creditorId,
        UUID portfolioId,
        String previousStatus,
        String newStatus,
        boolean eligible) implements IntegrationEvent {
}
