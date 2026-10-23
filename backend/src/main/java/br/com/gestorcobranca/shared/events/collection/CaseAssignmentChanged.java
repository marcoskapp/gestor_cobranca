package br.com.gestorcobranca.shared.events.collection;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Caso distribuído ou redistribuído (responsável humano e/ou equipe mudaram). Alimenta o modelo
 * de crédito por carteira e o denominador da taxa de recuperação por negociador (saldo elegível
 * atribuído).
 *
 * @param previousAssigneeId responsável anterior (null na primeira distribuição)
 * @param assigneeId         novo responsável (null = devolvido à fila)
 * @param teamId             equipe dona da fila do caso
 * @param eligibleBalance    saldo elegível do caso no momento da distribuição
 */
public record CaseAssignmentChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID caseId,
        UUID debtorId,
        UUID creditorId,
        UUID portfolioId,
        UUID previousAssigneeId,
        UUID assigneeId,
        UUID teamId,
        BigDecimal eligibleBalance) implements IntegrationEvent {
}
