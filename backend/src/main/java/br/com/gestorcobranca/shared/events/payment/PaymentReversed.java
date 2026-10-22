package br.com.gestorcobranca.shared.events.payment;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Estorno de pagamento confirmado: evento compensatório (o pagamento original não é apagado).
 * Reverte comissão apurada e pode reativar cobrança.
 */
public record PaymentReversed(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID reversalId,
        UUID paymentId,
        UUID debtorId,
        UUID creditorId,
        UUID portfolioId,
        UUID agreementId,
        BigDecimal amount,
        String reason) implements IntegrationEvent {
}
