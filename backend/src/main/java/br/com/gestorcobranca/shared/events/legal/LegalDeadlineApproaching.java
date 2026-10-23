package br.com.gestorcobranca.shared.events.legal;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Prazo jurídico pendente alcançou um nível de alerta (dias úteis restantes configurados na
 * assessoria, ex.: 5, 2 e 0) ou venceu sem cumprimento ({@code missed = true}). Publicado uma vez
 * por nível. Insumo para notificações (módulo communication) e painéis.
 *
 * @param kind                   natureza do prazo (nome do enum {@code DeadlineKind})
 * @param businessDaysRemaining  dias úteis até o vencimento na data do alerta (0 = vence hoje)
 * @param responsibleUserId      responsável pelo prazo (nulo = responsável do caso jurídico)
 * @param lawFirmId              escritório parceiro do caso jurídico, se houver
 */
public record LegalDeadlineApproaching(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID deadlineId,
        UUID matterId,
        UUID creditorId,
        UUID responsibleUserId,
        UUID lawFirmId,
        String kind,
        LocalDate dueDate,
        int businessDaysRemaining,
        boolean missed) implements IntegrationEvent {
}
