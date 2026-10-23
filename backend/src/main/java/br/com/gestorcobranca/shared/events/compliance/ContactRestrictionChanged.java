package br.com.gestorcobranca.shared.events.compliance;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Restrição de contato criada, revogada ou com vigência encerrada (opt-out, bloqueio jurídico,
 * solicitação do titular). Dispara o recálculo de elegibilidade (RB02) e a pausa de réguas.
 *
 * @param debtorId   devedor afetado; null quando a restrição é de um contato sem devedor identificado
 * @param channel    canal afetado (WHATSAPP, EMAIL, SMS, PHONE, PORTAL) ou null para todos
 * @param creditorId credor afetado ou null para todos
 * @param restrictionType tipo da restrição (nome do enum {@code RestrictionType})
 * @param restricted true = restrição criada; false = revogada ou vigência encerrada
 */
public record ContactRestrictionChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID restrictionId,
        UUID debtorId,
        UUID creditorId,
        String channel,
        String restrictionType,
        boolean restricted) implements IntegrationEvent {
}
