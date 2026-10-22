package br.com.gestorcobranca.shared.events.debt;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Título mudou de status ou de saldo (pagamento, suspensão, cancelamento, devolução).
 * Dispara o recálculo de elegibilidade (RB02) e a pausa de réguas.
 *
 * @param previousStatus status anterior (nome do enum {@code TitleStatus})
 * @param newStatus      novo status
 * @param openBalance    saldo em aberto após a mudança
 */
public record TitleStatusChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID titleId,
        UUID debtorId,
        UUID creditorId,
        UUID portfolioId,
        String previousStatus,
        String newStatus,
        BigDecimal openBalance) implements IntegrationEvent {
}
