package br.com.gestorcobranca.shared.events.negotiation;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Acordo mudou de estado (RB05): FORMALIZED, IN_PAYMENT, SETTLED, BROKEN, CANCELLED,
 * RENEGOTIATED.
 *
 * @param titleIds    títulos cobertos pelo acordo
 * @param totalAmount valor total negociado
 * @param replacedAgreementId acordo anterior quando é uma renegociação
 */
public record AgreementStatusChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID agreementId,
        UUID caseId,
        UUID debtorId,
        UUID creditorId,
        UUID portfolioId,
        List<UUID> titleIds,
        String previousStatus,
        String newStatus,
        BigDecimal totalAmount,
        UUID replacedAgreementId) implements IntegrationEvent {

    public AgreementStatusChanged {
        titleIds = titleIds == null ? List.of() : List.copyOf(titleIds);
    }
}
