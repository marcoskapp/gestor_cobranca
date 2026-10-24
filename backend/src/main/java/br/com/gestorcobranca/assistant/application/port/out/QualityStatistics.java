package br.com.gestorcobranca.assistant.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Leitura agregada para as métricas de qualidade do assistente (JDBC sob RLS). */
public interface QualityStatistics {

    /**
     * @param creditorIds credores permitidos (nulo = todos)
     */
    Snapshot snapshot(Instant from, Instant to, Set<UUID> creditorIds, UUID creditorId, UUID portfolioId,
                      int earlyBreakDays);

    /**
     * @param handoffsByReason transferências por motivo
     * @param guardrails       acionamentos por código
     * @param agreementsByOrigin acordos formalizados no período por origem da atribuição
     * @param earlyBreaksByOrigin dos acordos do período, quebrados até {@code earlyBreakDays} após a formalização
     */
    record Snapshot(long turns, long conversations, Map<String, Long> outcomes, Map<String, Long> handoffsByReason,
                    Map<String, Long> guardrails, BigDecimal cost, String costCurrency, long inputTokens,
                    long outputTokens, long avgLatencyMs, Map<String, Long> agreementsByOrigin,
                    Map<String, Long> earlyBreaksByOrigin, long reviewed, BigDecimal avgScore, long outOfPolicy,
                    long pendingReviews) {
    }
}
