package br.com.gestorcobranca.scoring.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Fato do relacionamento de cobrança usado nas features.
 *
 * @param caseId      caso, quando o produtor informa
 * @param referenceId promessa, acordo ou pagamento a que o fato se refere
 * @param occurredAt  instante em que o fato passou a ser conhecido (corte das features sem vazamento)
 * @param effectiveAt instante de efeito (data do pagamento; nos demais, igual a {@code occurredAt})
 */
public record CaseFact(FactKind kind, String status, UUID caseId, UUID debtorId, UUID creditorId, UUID portfolioId,
                       UUID referenceId, String channel, BigDecimal amount, Instant occurredAt, Instant effectiveAt) {
}
