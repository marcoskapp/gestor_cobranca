package br.com.gestorcobranca.assistant.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Avaliação do assistente com {@code ASSISTANT_REVIEW}: fila de turnos amostrados (taxa da política;
 * sempre os com guardrail ou falha), nota e veredito humanos, e métricas de qualidade por período
 * (transferências por motivo, guardrails, acordos do assistente × humanos, quebra precoce,
 * reclamações, custo por acordo). Escopo por credor; quem publicou a política aplicada no turno não o
 * avalia (segregação).
 */
public interface AssistantReviewUseCases {

    /** @param status PENDING (padrão) ou REVIEWED */
    PageResponse<ReviewView> queue(String status, UUID creditorId, PageQuery page);

    ReviewView submit(UUID reviewId, ReviewCommand command);

    QualityView quality(QualityQuery query);

    /** @param tags etiquetas de {@code TurnReview.TAGS} */
    record ReviewCommand(int score, String verdict, List<String> tags, String comment) {
    }

    record ReviewView(UUID id, UUID turnId, UUID conversationId, UUID creditorId, UUID portfolioId, UUID policyId,
                      String sampleReason, String status, Integer score, String verdict, List<String> tags,
                      String comment, UUID reviewerId, Instant reviewedAt, Instant createdAt) {
    }

    /** Período [from, to] em dias do fuso de operação. */
    record QualityQuery(LocalDate from, LocalDate to, UUID creditorId, UUID portfolioId) {
    }

    /**
     * @param handoffRate         transferências ÷ conversas atendidas
     * @param earlyBreakRate      por grupo (ASSISTANT, HUMAN): quebrados em até N dias ÷ formalizados no período
     * @param complaints          transferências por motivos de reclamação (Procon/advogado/processo, reclamação, opt-out)
     * @param costPerAgreement    custo do modelo ÷ acordos com participação do assistente (nulo sem acordos)
     */
    record QualityView(LocalDate from, LocalDate to, long turns, long conversations, Map<String, Long> outcomes,
                       Map<String, Long> handoffsByReason, BigDecimal handoffRate, Map<String, Long> guardrails,
                       Map<String, Long> agreementsByOrigin, Map<String, Long> agreementsByGroup,
                       Map<String, BigDecimal> earlyBreakRate, int earlyBreakDays, long complaints, BigDecimal cost,
                       String costCurrency, BigDecimal costPerAgreement, long inputTokens, long outputTokens,
                       long avgLatencyMs, long reviewed, BigDecimal avgScore, long outOfPolicy, long pendingReviews,
                       PixQuality pix) {
    }

    /**
     * Pix gerado pelo assistente na conversa (pedidos do período).
     *
     * @param issued           gerados e liberados para envio
     * @param deniedByReason   recusas por critério (ex.: CONVERSATION_RISK, AMOUNT_ABOVE_LIMIT)
     * @param suggested        sugestões ao operador (sombra/copiloto)
     * @param conversionRate   pagos ÷ gerados (nulo sem Pix gerado)
     * @param paidWithin24hRate pagos em até 24 h ÷ gerados
     */
    record PixQuality(long issued, long delivered, long denied, Map<String, Long> deniedByReason, long suggested,
                      long paid, long paidWithin24h, BigDecimal conversionRate, BigDecimal paidWithin24hRate) {
    }
}
