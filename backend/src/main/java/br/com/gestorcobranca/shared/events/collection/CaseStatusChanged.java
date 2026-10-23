package br.com.gestorcobranca.shared.events.collection;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Caso de cobrança aberto, suspenso, reaberto ou encerrado.
 *
 * @param previousStatus  null quando o caso acaba de ser aberto
 * @param eligible        resultado da elegibilidade (RB02) após a mudança
 * @param assigneeId      responsável humano atual do caso (null = na fila); acrescentado para o
 *                        módulo incentive (modelo de crédito por carteira e taxa de recuperação)
 * @param teamId          equipe dona da fila do caso, se houver
 * @param eligibleBalance saldo elegível do caso após a mudança (null = não informado)
 */
public record CaseStatusChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID caseId,
        UUID debtorId,
        UUID creditorId,
        UUID portfolioId,
        String previousStatus,
        String newStatus,
        boolean eligible,
        UUID assigneeId,
        UUID teamId,
        BigDecimal eligibleBalance) implements IntegrationEvent {

    /** Construtor da versão original do contrato (sem responsável, equipe e saldo). */
    public CaseStatusChanged(UUID eventId, UUID tenantId, Instant occurredAt, UUID caseId, UUID debtorId,
                             UUID creditorId, UUID portfolioId, String previousStatus, String newStatus,
                             boolean eligible) {
        this(eventId, tenantId, occurredAt, caseId, debtorId, creditorId, portfolioId, previousStatus, newStatus,
                eligible, null, null, null);
    }
}
