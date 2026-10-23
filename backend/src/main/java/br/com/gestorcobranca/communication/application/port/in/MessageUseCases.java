package br.com.gestorcobranca.communication.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Envio de mensagens (operador, assistente, campanhas e sistema). O pedido é validado, renderizado
 * e gravado como {@code QUEUED}; o envio é assíncrono (job {@code communication.send}, com
 * retentativas e DEAD) e passa <strong>sempre</strong> por {@code ContactGate#authorizeAndRecord}
 * imediatamente antes da entrega ao provedor. Uma verificação prévia ({@code ContactGate#evaluate})
 * registra como {@code FAILED/CONTACT_DENIED} o pedido já negado, sem enfileirar.
 * <p>
 * Regras: WhatsApp fora da janela de 24 h só com template aprovado; campanhas e sistema só com
 * template; o assistente só envia em conversas que conduz (dono {@code ASSISTANT}); operador
 * precisa poder trabalhar a conversa (dono, sem dono, ou {@code CASE_ASSIGN}) e passa a ser o dono.
 * Chave de idempotência repetida devolve a mensagem já criada.
 */
public interface MessageUseCases {

    MessageView send(SendMessageCommand command);

    MessageView get(UUID messageId);

    /** Mensagens da conversa, das mais recentes para as mais antigas. */
    PageResponse<MessageView> listByConversation(UUID conversationId, PageQuery page);

    /**
     * Linha do tempo do devedor: mensagens de todas as suas conversas (todos os canais), das mais
     * recentes para as mais antigas, somente das conversas nos credores do escopo do ator.
     *
     * @param creditorId restringe a um credor (opcional; fora do escopo → página vazia)
     */
    PageResponse<MessageView> listByDebtor(UUID debtorId, UUID creditorId, PageQuery page);

    /**
     * Destino: {@code conversationId} (resposta numa conversa) <em>ou</em> devedor + credor + canal
     * (abre ou reutiliza a conversa do contato). Conteúdo: {@code templateCode} + {@code variables}
     * <em>ou</em> {@code text} livre.
     *
     * @param caseId              caso (opcional; sem ele, o caso ativo do devedor no credor, se único)
     * @param contactId           contato do devedor (opcional; padrão = o de maior prioridade do canal)
     * @param campaignExecutionId execução de campanha que origina o envio (autor CAMPAIGN)
     * @param idempotencyKey      chave do cliente (até 100) para reenvios seguros
     */
    record SendMessageCommand(UUID conversationId, UUID debtorId, UUID creditorId, UUID caseId, String channel,
                              UUID contactId, String templateCode, Map<String, String> variables, String text,
                              UUID campaignExecutionId, String idempotencyKey) {
    }

    /**
     * @param senderType  USER, ASSISTANT, CAMPAIGN, SYSTEM ou DEBTOR
     * @param status      QUEUED, SENT, DELIVERED, READ, FAILED ou RECEIVED
     * @param failureCode {@code CONTACT_DENIED} (porteiro; motivos em {@code failureDetail}),
     *                    {@code RETRIES_EXHAUSTED}, {@code ACCOUNT_UNAVAILABLE}, código do provedor…
     * @param content     texto da mensagem (nulo após o descarte pela retenção)
     */
    record MessageView(UUID id, UUID conversationId, String channel, String direction, String senderType,
                       UUID senderId, UUID templateId, String subject, String content, boolean contentPurged,
                       String status, Instant statusAt, String failureCode, String failureDetail, int sendAttempts,
                       UUID campaignExecutionId, Instant queuedAt, Instant sentAt, Instant deliveredAt, Instant readAt,
                       Instant failedAt, Instant receivedAt, Instant createdAt) {
    }
}
