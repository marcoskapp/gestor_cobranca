package br.com.gestorcobranca.shared.events.organization;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.UUID;

/** Carteira ativada, suspensa, devolvida ou encerrada. Afeta elegibilidade e campanhas. */
public record PortfolioStatusChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID portfolioId,
        UUID creditorId,
        String previousStatus,
        String newStatus) implements IntegrationEvent {
}
