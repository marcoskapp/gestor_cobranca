package br.com.gestorcobranca.scoring.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Posição do caso de cobrança no instante do cálculo (lida do {@code collection}).
 *
 * @param status       OPEN, IN_NEGOTIATION, PROMISE, AGREEMENT, DISPUTED, SUSPENDED, LEGAL
 * @param resumeStatus estágio guardado durante uma retenção
 */
public record CaseInputs(UUID caseId, UUID debtorId, UUID creditorId, UUID portfolioId, UUID assigneeUserId,
                         String status, String resumeStatus, long daysOverdue, BigDecimal openBalance, int openTitles,
                         Instant openedAt, Instant lastEffectiveContactAt) {
}
