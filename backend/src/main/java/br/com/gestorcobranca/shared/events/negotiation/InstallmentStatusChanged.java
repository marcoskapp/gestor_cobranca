package br.com.gestorcobranca.shared.events.negotiation;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Parcela de acordo paga, parcialmente paga, vencida ou cancelada. */
public record InstallmentStatusChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID installmentId,
        UUID agreementId,
        UUID debtorId,
        UUID creditorId,
        int number,
        LocalDate dueDate,
        BigDecimal amount,
        BigDecimal paidAmount,
        String previousStatus,
        String newStatus) implements IntegrationEvent {
}
