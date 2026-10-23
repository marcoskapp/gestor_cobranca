package br.com.gestorcobranca.shared.events.compliance;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Nova versão de política de contato publicada (janela, feriados, limites). A elegibilidade de
 * contato dos devedores alcançados deve ser recalculada a partir de {@code effectiveFrom}.
 *
 * @param creditorId credor da política; null = política padrão da assessoria
 */
public record ContactPolicyPublished(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID policyId,
        UUID creditorId,
        int policyVersion,
        Instant effectiveFrom) implements IntegrationEvent {
}
