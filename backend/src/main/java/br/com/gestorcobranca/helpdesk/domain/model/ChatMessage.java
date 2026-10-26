package br.com.gestorcobranca.helpdesk.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Mensagem de uma conversa: a pergunta do usuário ou a resposta do chat. O conteúdo (texto, cartões, ações) fica
 * cifrado ({@code content_encrypted}, AES-256-GCM com a assessoria no AAD) e é descartado pela retenção; ficam os
 * metadados (tema, resultado, consumo, avaliação). A tabela só aceita a avaliação, o descarte e o vínculo com o
 * chamado como alteração (privilégio de coluna e gatilho).
 */
@Entity
@DynamicUpdate
@Table(name = "chat_message", schema = "helpdesk")
public class ChatMessage extends TenantScopedEntity {

    public static final int MAX_CONTENT = 60_000;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "sequence", nullable = false, updatable = false)
    private int sequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 12, updatable = false)
    private MessageRole role;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "content_encrypted", length = 100000)
    private String content;

    @Column(name = "content_purged_at")
    private Instant contentPurgedAt;

    @Column(name = "screen_key", length = 64, updatable = false)
    private String screenKey;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 20, updatable = false)
    private AnswerOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "topic", length = 30, updatable = false)
    private QuestionTopic topic;

    @Column(name = "prompt_ref", length = 60, updatable = false)
    private String promptRef;

    @Column(name = "prompt_sha256", length = 64, updatable = false)
    private String promptSha256;

    @Column(name = "knowledge_version", length = 32, updatable = false)
    private String knowledgeVersion;

    @Column(name = "provider", length = 20, updatable = false)
    private String provider;

    @Column(name = "model", length = 200, updatable = false)
    private String model;

    @Column(name = "model_calls", nullable = false, updatable = false)
    private int modelCalls;

    @Column(name = "tool_calls", nullable = false, updatable = false)
    private int toolCalls;

    @Column(name = "input_tokens", nullable = false, updatable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false, updatable = false)
    private long outputTokens;

    @Column(name = "cache_read_tokens", nullable = false, updatable = false)
    private long cacheReadTokens;

    @Column(name = "cache_write_tokens", nullable = false, updatable = false)
    private long cacheWriteTokens;

    @Column(name = "cost_amount", nullable = false, precision = 19, scale = 6, updatable = false)
    private BigDecimal costAmount;

    @Column(name = "cost_currency", length = 3, updatable = false)
    private String costCurrency;

    @Column(name = "latency_ms", updatable = false)
    private Long latencyMs;

    @Column(name = "error_code", length = 60, updatable = false)
    private String errorCode;

    @Column(name = "ticket_id")
    private UUID ticketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "feedback", length = 4)
    private Feedback feedback;

    @Column(name = "feedback_reason", length = 30)
    private String feedbackReason;

    @Column(name = "feedback_at")
    private Instant feedbackAt;

    protected ChatMessage() {
    }

    private ChatMessage(UUID conversationId, UUID userId, int sequence, MessageRole role, String content,
                        String screenKey, Instant at) {
        Guard.require(content != null && content.length() <= MAX_CONTENT, "helpdesk.message_too_long",
                "Mensagem longa demais");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.sequence = sequence;
        this.role = role;
        this.content = content;
        this.screenKey = screenKey;
        this.occurredAt = at;
        this.costAmount = BigDecimal.ZERO;
    }

    public static ChatMessage question(UUID conversationId, UUID userId, int sequence, String content, String screen,
                                       Instant at) {
        return new ChatMessage(conversationId, userId, sequence, MessageRole.USER, content, screen, at);
    }

    /** Resposta com o consumo do modelo; {@code usage} nulo quando o modelo não foi chamado. */
    public static ChatMessage answer(UUID conversationId, UUID userId, int sequence, String content, String screen,
                                     Instant at, AnswerOutcome outcome, QuestionTopic topic, AnswerUsage usage) {
        ChatMessage m = new ChatMessage(conversationId, userId, sequence, MessageRole.ASSISTANT, content, screen, at);
        m.outcome = Objects.requireNonNull(outcome, "outcome");
        m.topic = topic;
        if (usage != null) {
            m.promptRef = usage.promptRef();
            m.promptSha256 = usage.promptSha256();
            m.knowledgeVersion = usage.knowledgeVersion();
            m.provider = usage.provider();
            m.model = usage.model();
            m.modelCalls = usage.modelCalls();
            m.toolCalls = usage.toolCalls();
            m.inputTokens = usage.inputTokens();
            m.outputTokens = usage.outputTokens();
            m.cacheReadTokens = usage.cacheReadTokens();
            m.cacheWriteTokens = usage.cacheWriteTokens();
            m.costAmount = usage.cost() == null ? BigDecimal.ZERO : usage.cost();
            m.costCurrency = usage.currency();
            m.latencyMs = usage.latencyMs();
            m.errorCode = usage.errorCode();
        }
        return m;
    }

    /**
     * Consumo de uma resposta.
     *
     * @param inputTokens tokens do prompt (com e sem cache), base do limite por pergunta
     */
    public record AnswerUsage(String promptRef, String promptSha256, String knowledgeVersion, String provider,
                              String model, int modelCalls, int toolCalls, long inputTokens, long outputTokens,
                              long cacheReadTokens, long cacheWriteTokens, BigDecimal cost, String currency,
                              Long latencyMs, String errorCode) {
    }

    public void rate(Feedback value, String reason, Instant at) {
        Guard.require(role == MessageRole.ASSISTANT, "helpdesk.feedback_not_answer", "Só respostas do chat são avaliadas");
        Guard.require(value != null, "helpdesk.feedback_invalid", "Avaliação inválida");
        Guard.require(reason == null || value == Feedback.DOWN, "helpdesk.feedback_reason_only_down",
                "O motivo só acompanha a avaliação negativa");
        this.feedback = value;
        this.feedbackReason = reason;
        this.feedbackAt = at;
    }

    public void linkTicket(UUID ticket) {
        this.ticketId = ticket;
    }

    /** Retenção: descarta o conteúdo, mantém metadados e consumo. */
    public void purgeContent(Instant at) {
        this.content = null;
        this.contentPurgedAt = at;
    }

    public boolean purged() {
        return contentPurgedAt != null;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public int getSequence() {
        return sequence;
    }

    public MessageRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getScreenKey() {
        return screenKey;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public AnswerOutcome getOutcome() {
        return outcome;
    }

    public QuestionTopic getTopic() {
        return topic;
    }

    public Feedback getFeedback() {
        return feedback;
    }

    public UUID getTicketId() {
        return ticketId;
    }

    public BigDecimal getCostAmount() {
        return costAmount;
    }

    public long getInputTokens() {
        return inputTokens;
    }

    public long getOutputTokens() {
        return outputTokens;
    }

    public String getModel() {
        return model;
    }
}
