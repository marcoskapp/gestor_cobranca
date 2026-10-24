package br.com.gestorcobranca.assistant.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Trilha do assistente com {@code ASSISTANT_REVIEW} e o credor da conversa no escopo (404 fora dele):
 * sessão da conversa, turnos e o detalhe de cada turno (entrada, contexto enviado, resposta, chamadas
 * de ferramenta). O detalhe com conteúdo registra {@code SENSITIVE_DATA_VIEWED} no log de acesso.
 */
public interface AssistantTrailUseCases {

    Optional<SessionView> session(UUID conversationId);

    PageResponse<TurnView> turns(UUID conversationId, PageQuery page);

    TurnDetail turn(UUID turnId);

    record SessionView(UUID id, UUID conversationId, UUID creditorId, UUID portfolioId, UUID caseId, UUID debtorId,
                       String status, int episode, int turns, int totalTurns, String identityStatus,
                       int misunderstandings, String handoffReason, Instant handedOffAt, long inputTokens,
                       long outputTokens, BigDecimal cost, String costCurrency, UUID lastPolicyId,
                       boolean turnInProgress) {
    }

    record TurnView(UUID id, UUID conversationId, UUID policyId, Integer policyVersion, String promptCode,
                    Integer promptVersion, String mode, int episode, int turnNumber, String outcome,
                    String handoffReason, String triggerSource, List<String> guardrails, String intent,
                    BigDecimal confidence, String provider, String model, int modelCalls, int toolCalls,
                    long inputTokens, long outputTokens, BigDecimal cost, String costCurrency, long modelLatencyMs,
                    long totalLatencyMs, UUID outboundMessageId, UUID draftId, UUID proposalId, UUID agreementId,
                    String errorCode, String contextSha256, int contextVersion, Instant startedAt, Instant finishedAt,
                    boolean contentPurged) {
    }

    /**
     * @param inputText   texto recebido do devedor (nulo após retenção/anonimização)
     * @param context     contexto minimizado enviado ao modelo (JSON)
     * @param pixRequests pedidos de Pix do turno com os critérios avaliados no servidor
     */
    record TurnDetail(TurnView turn, String inputText, String context, String outputText, List<ToolCallView> toolCalls,
                      List<PixRequestView> pixRequests) {
    }

    /**
     * Pedido de Pix na conversa (ferramenta {@code generate_payment}).
     *
     * @param decision       ISSUED, DENIED ou SUGGESTED
     * @param denialCode     critério que recusou (ex.: AMOUNT_ABOVE_LIMIT, CONVERSATION_RISK)
     * @param deliveryStatus AWAITING, DELIVERED, CANCELLED ou NOT_DELIVERED
     */
    record PixRequestView(UUID id, String decision, String denialCode, List<CriterionView> criteria, UUID agreementId,
                          Integer installmentNumber, BigDecimal amount, UUID instructionId, boolean reusedInstruction,
                          Instant expiresAt, String deliveryStatus, String deliveryCode, Instant deliveredAt,
                          Instant paidAt, Instant requestedAt) {
    }

    /** @param detail observação do servidor (situação, motivos, limite), sem dados pessoais */
    record CriterionView(String criterion, boolean passed, String detail) {
    }

    record ToolCallView(int sequence, String tool, String arguments, String result, String outcome, String errorCode,
                        String referenceType, UUID referenceId, long durationMs) {
    }
}
