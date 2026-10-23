package br.com.gestorcobranca.communication.application.port.in;

import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.MessageView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Conversas e caixa de entrada (F12): listagem por dono/fila/perfil exigido, atribuição, assumir,
 * devolver à fila ou ao assistente, transferência do assistente para humano (motivo, resumo e
 * perfil exigido) e rascunhos do assistente (modos sombra/copiloto).
 * <p>
 * Dono do atendimento: em conversa com caso, toda mudança é aplicada primeiro no caso
 * ({@code CaseWorkUseCases} do collection — fonte única do dono do caso e do histórico de
 * atribuição) e então nas conversas abertas do caso, com registro em {@code ConversationHandoff},
 * auditoria e evento de negócio. Escopo por credor (404 leitura, 403 comando); conversa sem credor
 * (contato não identificado) exige acesso a todos os credores.
 */
public interface ConversationUseCases {

    PageResponse<ConversationView> inbox(InboxFilter filter, PageQuery page);

    /**
     * Contagem das conversas abertas por aba (minhas, fila — inclui sem dono —, assistente e todas)
     * no escopo do ator, numa única consulta; {@code breached} conta as que estouraram a meta de
     * primeira resposta do canal.
     *
     * @param channel       canal (opcional)
     * @param awaitingReply somente aguardando resposta
     */
    InboxCounts counts(String channel, Boolean awaitingReply);

    ConversationDetail get(UUID conversationId);

    ConversationView markRead(UUID conversationId);

    /**
     * Identifica o devedor de uma conversa iniciada por contato desconhecido: o contato precisa
     * constar do cadastro do devedor; o credor é o da conta (conta de credor) ou o informado; o caso
     * ativo do devedor no credor, se único, é vinculado. Só então mensagens podem ser enviadas.
     */
    ConversationView identify(UUID conversationId, UUID debtorId, UUID creditorId);

    /** O operador corrente assume a conversa (da fila, sem dono ou do assistente). */
    ConversationView takeOver(UUID conversationId, String reason);

    /** Devolve à fila (o dono, ou quem tem {@code CASE_ASSIGN}). */
    ConversationView release(UUID conversationId, String reason);

    /** Atribui a um operador (conversa com caso; o collection valida operador e escopo). */
    ConversationView assign(UUID conversationId, UUID userId, String reason);

    /** Devolve ao assistente de IA (o dono, ou quem tem {@code CASE_ASSIGN}). */
    ConversationView returnToAssistant(UUID conversationId, String reason);

    /**
     * Transferência para humanos (assistente, sistema ou supervisor): a conversa vai para a fila com
     * motivo, resumo e perfil exigido; o caso volta à fila no collection.
     */
    ConversationView handoffToHumans(UUID conversationId, HandoffCommand command);

    ConversationView close(UUID conversationId, String reason);

    List<HandoffView> handoffs(UUID conversationId);

    /** Rascunho/sugestão do assistente para o operador (nada é enviado sem humano). */
    DraftView proposeDraft(UUID conversationId, ProposeDraftCommand command);

    /** O operador envia o rascunho (com edição opcional) — mesmas regras de {@code MessageUseCases#send}. */
    MessageView sendDraft(UUID conversationId, UUID draftId, String editedContent);

    DraftView discardDraft(UUID conversationId, UUID draftId);

    /**
     * @param ownerTypes   ASSISTANT, QUEUE, USER, NONE (vazio = todos)
     * @param ownerUserId  operador dono ("me" na API)
     * @param status       OPEN (padrão na API) ou CLOSED
     * @param awaitingReply somente com mensagem do devedor sem resposta
     */
    record InboxFilter(UUID creditorId, UUID portfolioId, UUID debtorId, UUID caseId, String channel, String status,
                       Set<String> ownerTypes, UUID ownerUserId, UUID teamId, String requiredProfile,
                       Boolean awaitingReply) {
    }

    /**
     * @param reasonCode      motivo estável (ex.: REQUIRES_APPROVAL, DISPUTE, DEBTOR_REQUESTED_HUMAN)
     * @param summary         resumo para o operador (obrigatório; até 2000)
     * @param requiredProfile perfil exigido (ex.: SUPERVISOR); nulo = qualquer operador
     */
    record HandoffCommand(String reasonCode, String summary, String requiredProfile, String referenceType,
                          UUID referenceId) {
    }

    /** @param mode SHADOW ou COPILOT */
    record ProposeDraftCommand(String mode, String content, String rationale) {
    }

    /**
     * @param sessionWindowEndsAt fim da janela de 24 h do WhatsApp (nulo = fechada ou outro canal)
     * @param waitingSeconds      espera da primeira mensagem sem resposta (SLA), se houver
     * @param slaStatus           OK, WARNING ou BREACHED pelas metas de primeira resposta da assessoria
     *                            para o canal (nulo sem espera)
     * @param slaWarningAt        instante do aviso da espera atual (nulo sem espera)
     * @param slaBreachAt         instante do limite da espera atual (nulo sem espera)
     */
    record ConversationView(UUID id, UUID debtorId, UUID creditorId, UUID portfolioId, UUID caseId, String channel,
                            UUID channelAccountId, String contactMasked, String status, String ownerType,
                            UUID ownerUserId, UUID ownerTeamId, String requiredProfile, Instant ownerSince,
                            Instant lastInboundAt, Instant lastOutboundAt, Instant lastMessageAt, Instant awaitingSince,
                            Long waitingSeconds, int unreadCount, int messageCount, boolean sessionWindowOpen,
                            Instant sessionWindowEndsAt, Instant closedAt, String closeReason, Instant createdAt,
                            long version, String slaStatus, Instant slaWarningAt, Instant slaBreachAt) {
    }

    record InboxCounts(long mine, long queue, long assistant, long all, long breached) {
    }

    record ConversationDetail(ConversationView conversation, List<HandoffView> handoffs, List<DraftView> pendingDrafts) {
    }

    /** @param kind ESCALATION, TAKE_OVER, ASSIGNMENT, RELEASE, RETURN_TO_ASSISTANT, CLOSE, REOPEN ou CASE_SYNC */
    record HandoffView(UUID id, UUID conversationId, UUID caseId, String fromOwnerType, UUID fromUserId,
                       String toOwnerType, UUID toUserId, UUID toTeamId, String kind, String reasonCode, String summary,
                       String requiredProfile, String actorType, UUID actorId, String referenceType, UUID referenceId,
                       Instant occurredAt) {
    }

    record DraftView(UUID id, UUID conversationId, String mode, String authorType, UUID authorId, String content,
                     String rationale, String status, UUID decidedBy, Instant decidedAt, boolean edited, UUID messageId,
                     Instant createdAt) {
    }
}
