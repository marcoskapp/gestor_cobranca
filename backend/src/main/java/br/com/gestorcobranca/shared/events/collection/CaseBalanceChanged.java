package br.com.gestorcobranca.shared.events.collection;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Saldo elegível do caso mudou (pagamento, carga de títulos, baixa, contestação) sem mudança de
 * status, elegibilidade ou responsável — nesses casos o saldo já segue em {@link CaseStatusChanged}
 * ou {@link CaseAssignmentChanged}. Junto com eles, permite saber o saldo elegível atribuído a
 * cada negociador em qualquer instante (denominador da taxa de recuperação do incentive).
 *
 * @param assigneeId      responsável humano atual (null = na fila)
 * @param teamId          equipe dona da fila do caso, se houver
 * @param previousEligibleBalance saldo elegível atribuível antes da mudança
 * @param eligible        resultado da elegibilidade (RB02) no momento
 * @param eligibleBalance saldo elegível atribuível após a mudança (zero quando o caso está inelegível)
 */
public record CaseBalanceChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID caseId,
        UUID debtorId,
        UUID creditorId,
        UUID portfolioId,
        UUID assigneeId,
        UUID teamId,
        boolean eligible,
        BigDecimal previousEligibleBalance,
        BigDecimal eligibleBalance) implements IntegrationEvent {
}
