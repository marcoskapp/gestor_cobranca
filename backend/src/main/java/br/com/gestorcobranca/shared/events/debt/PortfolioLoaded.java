package br.com.gestorcobranca.shared.events.debt;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Lote de importação concluído: títulos criados/atualizados numa carteira. O módulo
 * {@code collection} abre ou atualiza casos para os devedores afetados (consultando os
 * títulos pelo {@code importBatchId}).
 */
public record PortfolioLoaded(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID importBatchId,
        UUID creditorId,
        UUID portfolioId,
        int titlesCreated,
        int titlesUpdated,
        int debtorsAffected) implements IntegrationEvent {
}
