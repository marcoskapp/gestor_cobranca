package br.com.gestorcobranca.communication.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Mensagem de uma conversa. Conteúdo, assunto e variáveis ficam criptografados; o conteúdo é
 * descartado pela retenção ({@code MESSAGE_CONTENT}) mantendo os metadados.
 * <p>
 * Saída: nasce {@code QUEUED}; o envio (job) autoriza no porteiro do compliance e entrega ao
 * provedor ({@code SENT}) ou falha ({@code FAILED} — negação do porteiro, rejeição do provedor ou
 * tentativas esgotadas). Webhooks avançam para {@code DELIVERED}/{@code READ} sem regredir.
 * Entrada: {@code RECEIVED}, idempotente pelo id do provedor.
 */
@Entity
@Table(name = "message", schema = "communication")
public class Message extends TenantScopedEntity {

    public static final int CONTENT_MAX = 20_000;
    public static final String CONTACT_DENIED = "CONTACT_DENIED";

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20, updatable = false)
    private ChannelType channel;

    @Column(name = "channel_account_id", nullable = false, updatable = false)
    private UUID channelAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10, updatable = false)
    private MessageDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false, length = 20, updatable = false)
    private SenderType senderType;

    @Column(name = "sender_id", updatable = false)
    private UUID senderId;

    @Column(name = "template_id", updatable = false)
    private UUID templateId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "variables_encrypted", length = 12000)
    private String variables;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "subject_encrypted", length = 2000)
    private String subject;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "content_encrypted", length = 100000)
    private String content;

    @Column(name = "content_purged_at")
    private Instant contentPurgedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MessageStatus status;

    @Column(name = "status_at", nullable = false)
    private Instant statusAt;

    @Column(name = "provider_message_id", length = 200)
    private String providerMessageId;

    @Column(name = "contact_attempt_id")
    private UUID contactAttemptId;

    @Column(name = "send_attempts", nullable = false)
    private int sendAttempts;

    @Column(name = "failure_code", length = 60)
    private String failureCode;

    @Column(name = "failure_detail", length = 500)
    private String failureDetail;

    @Column(name = "campaign_execution_id", updatable = false)
    private UUID campaignExecutionId;

    @Column(name = "draft_id", updatable = false)
    private UUID draftId;

    @Column(name = "idempotency_key", length = 100, updatable = false)
    private String idempotencyKey;

    @Column(name = "queued_at")
    private Instant queuedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "received_at")
    private Instant receivedAt;

    protected Message() {
    }

    /** Dados de uma mensagem de saída já renderizada. */
    public record Outbound(SenderType senderType, UUID senderId, UUID templateId, String variablesJson, String subject,
                           String content, UUID campaignExecutionId, UUID draftId, String idempotencyKey) {
    }

    public static Message outbound(Conversation conversation, Outbound data, Instant now) {
        Guard.require(data.senderType() != null && data.senderType() != SenderType.DEBTOR, "message.sender_invalid",
                "Autor inválido para mensagem de saída");
        Guard.require(data.content() != null && !data.content().isBlank(), "message.content_required",
                "Conteúdo da mensagem obrigatório");
        Guard.require(data.content().length() <= CONTENT_MAX, "message.content_too_long",
                "Mensagem com no máximo %d caracteres".formatted(CONTENT_MAX));
        Guard.require(data.idempotencyKey() == null || data.idempotencyKey().length() <= 100,
                "message.idempotency_key_invalid", "Chave de idempotência até 100 caracteres");
        Message m = new Message();
        m.conversationId = conversation.getId();
        m.channel = conversation.getChannel();
        m.channelAccountId = conversation.getChannelAccountId();
        m.direction = MessageDirection.OUTBOUND;
        m.senderType = data.senderType();
        m.senderId = data.senderId();
        m.templateId = data.templateId();
        m.variables = data.variablesJson();
        m.subject = data.subject();
        m.content = data.content();
        m.campaignExecutionId = data.campaignExecutionId();
        m.draftId = data.draftId();
        m.idempotencyKey = data.idempotencyKey();
        m.status = MessageStatus.QUEUED;
        m.statusAt = now;
        m.queuedAt = now;
        return m;
    }

    public static Message inbound(Conversation conversation, String providerMessageId, String content, Instant receivedAt) {
        Guard.require(providerMessageId != null && !providerMessageId.isBlank() && providerMessageId.length() <= 200,
                "message.provider_id_required", "Id do provedor obrigatório");
        Message m = new Message();
        m.conversationId = conversation.getId();
        m.channel = conversation.getChannel();
        m.channelAccountId = conversation.getChannelAccountId();
        m.direction = MessageDirection.INBOUND;
        m.senderType = SenderType.DEBTOR;
        m.senderId = conversation.getDebtorId();
        String text = content == null ? "" : content;
        m.content = text.length() > CONTENT_MAX ? text.substring(0, CONTENT_MAX) : text;
        m.providerMessageId = providerMessageId;
        m.status = MessageStatus.RECEIVED;
        m.statusAt = receivedAt;
        m.receivedAt = receivedAt;
        return m;
    }

    /** Número da tentativa de envio em curso (1 = primeira; retentativas do job). */
    public void recordAttempt(int attempt) {
        requireQueued();
        Guard.require(attempt >= 1, "message.attempt_invalid", "Tentativa inválida");
        this.sendAttempts = Math.max(sendAttempts, attempt);
    }

    public void markSent(String providerMessageId, UUID contactAttemptId, Instant at) {
        requireQueued();
        Guard.require(providerMessageId != null && !providerMessageId.isBlank() && providerMessageId.length() <= 200,
                "message.provider_id_required", "Id do provedor obrigatório");
        this.providerMessageId = providerMessageId;
        this.contactAttemptId = contactAttemptId;
        this.status = MessageStatus.SENT;
        this.statusAt = at;
        this.sentAt = at;
    }

    /** Envio negado pelo porteiro de contato (restrição, janela, limites). */
    public void markBlocked(String reasons, Instant at) {
        requireQueued();
        fail(CONTACT_DENIED, reasons, at);
    }

    /** Falha definitiva (rejeição do provedor, tentativas esgotadas, conta indisponível). */
    public void markFailed(String code, String detail, Instant at) {
        Guard.requireState(direction == MessageDirection.OUTBOUND && !status.isFinal(), "message.not_pending",
                "A mensagem não está pendente de envio");
        fail(code, detail, at);
    }

    private void fail(String code, String detail, Instant at) {
        Guard.require(code != null && !code.isBlank(), "message.failure_code_required", "Código da falha obrigatório");
        this.status = MessageStatus.FAILED;
        this.statusAt = at;
        this.failedAt = at;
        this.failureCode = code.length() > 60 ? code.substring(0, 60) : code;
        this.failureDetail = detail == null ? null : detail.length() > 500 ? detail.substring(0, 500) : detail;
    }

    /**
     * Status informado pelo provedor (webhook). Não regride: {@code READ} depois de
     * {@code DELIVERED} avança; {@code DELIVERED} depois de {@code READ} é ignorado; falha depois de
     * lida é ignorada.
     *
     * @return true se o status mudou
     */
    public boolean applyProviderStatus(MessageStatus reported, String failureCode, String failureDetail, Instant at) {
        if (direction != MessageDirection.OUTBOUND || status == MessageStatus.FAILED || status == MessageStatus.QUEUED) {
            return false;
        }
        switch (reported) {
            case DELIVERED, READ -> {
                if (reported.rank() <= status.rank()) {
                    return false;
                }
                if (deliveredAt == null) {
                    this.deliveredAt = at;
                }
                if (reported == MessageStatus.READ) {
                    this.readAt = at;
                }
                this.status = reported;
                this.statusAt = at;
                return true;
            }
            case FAILED -> {
                if (status == MessageStatus.READ) {
                    return false;
                }
                fail(failureCode == null ? "PROVIDER_FAILED" : failureCode, failureDetail, at);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Descarte do conteúdo pela política de retenção (metadados permanecem). */
    public boolean purgeContent(Instant now) {
        if (contentPurgedAt != null) {
            return false;
        }
        this.content = null;
        this.subject = null;
        this.variables = null;
        this.contentPurgedAt = now;
        return true;
    }

    private void requireQueued() {
        Guard.requireState(status == MessageStatus.QUEUED, "message.not_queued", "A mensagem não está na fila de envio");
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public ChannelType getChannel() {
        return channel;
    }

    public UUID getChannelAccountId() {
        return channelAccountId;
    }

    public MessageDirection getDirection() {
        return direction;
    }

    public SenderType getSenderType() {
        return senderType;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public String getVariables() {
        return variables;
    }

    public String getSubject() {
        return subject;
    }

    public String getContent() {
        return content;
    }

    public Instant getContentPurgedAt() {
        return contentPurgedAt;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public Instant getStatusAt() {
        return statusAt;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public UUID getContactAttemptId() {
        return contactAttemptId;
    }

    public int getSendAttempts() {
        return sendAttempts;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureDetail() {
        return failureDetail;
    }

    public UUID getCampaignExecutionId() {
        return campaignExecutionId;
    }

    public UUID getDraftId() {
        return draftId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
