package br.com.gestorcobranca.collection.domain.model;

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
 * Resposta fundamentada ao devedor sobre o resultado da contestação: mensagem transacional enviada pelo módulo de
 * comunicação (acompanha a situação da mensagem) ou registro de entrega por outro meio. Texto criptografado.
 */
@Entity
@Table(name = "dispute_debtor_notice", schema = "collection")
public class DisputeDebtorNotice extends TenantScopedEntity {

    public static final int BODY_MAX = 8000;

    @Column(name = "dispute_id", nullable = false, updatable = false)
    private UUID disputeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20, updatable = false)
    private DebtorNoticeChannel channel;

    @Column(name = "message_id", updatable = false)
    private UUID messageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DebtorNoticeDelivery status;

    @Column(name = "failure_detail", length = 500)
    private String failureDetail;

    @Column(name = "template_key", length = 30, updatable = false)
    private String templateKey;

    @Column(name = "template_version", updatable = false)
    private Integer templateVersion;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "subject_encrypted", length = 2000, updatable = false)
    private String subject;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "body_encrypted", nullable = false, length = 48000, updatable = false)
    private String body;

    @Column(name = "sent_by", nullable = false, updatable = false)
    private UUID sentBy;

    @Column(name = "status_at", nullable = false)
    private Instant statusAt;

    protected DisputeDebtorNotice() {
    }

    public DisputeDebtorNotice(UUID disputeId, DebtorNoticeChannel channel, UUID messageId, DebtorNoticeDelivery status,
                               String failureDetail, String templateKey, Integer templateVersion, String subject,
                               String body, UUID sentBy, Instant now) {
        Guard.require(channel != null && status != null, "dispute_notice.channel_required", "Canal obrigatório");
        Guard.require((channel == DebtorNoticeChannel.OTHER) == (messageId == null), "dispute_notice.message_invalid",
                "Mensagem do canal inválida");
        Guard.require(body != null && !body.isBlank() && body.trim().length() <= BODY_MAX, "dispute_notice.body_required",
                "Texto da resposta obrigatório (até %d caracteres)".formatted(BODY_MAX));
        this.disputeId = disputeId;
        this.channel = channel;
        this.messageId = messageId;
        this.status = status;
        this.failureDetail = truncate(failureDetail);
        this.templateKey = templateKey;
        this.templateVersion = templateVersion;
        this.subject = subject == null || subject.isBlank() ? null : subject.trim();
        this.body = body.trim();
        this.sentBy = sentBy;
        this.statusAt = now;
    }

    /** Situação da mensagem no communication (nunca regride: entregue/lida não voltam a enviada). */
    public boolean updateFromMessage(String messageStatus, String failureCode, String detail, Instant at) {
        DebtorNoticeDelivery next = switch (messageStatus) {
            case "SENT" -> DebtorNoticeDelivery.SENT;
            case "DELIVERED" -> DebtorNoticeDelivery.DELIVERED;
            case "READ" -> DebtorNoticeDelivery.READ;
            case "FAILED" -> "CONTACT_DENIED".equals(failureCode) ? DebtorNoticeDelivery.BLOCKED : DebtorNoticeDelivery.FAILED;
            default -> null;
        };
        if (next == null || next == status || rank(next) < rank(status)) {
            return false;
        }
        status = next;
        statusAt = at;
        if (next == DebtorNoticeDelivery.FAILED || next == DebtorNoticeDelivery.BLOCKED) {
            failureDetail = truncate(detail != null ? detail : failureCode);
        }
        return true;
    }

    public boolean isDelivered() {
        return status == DebtorNoticeDelivery.SENT || status == DebtorNoticeDelivery.DELIVERED
                || status == DebtorNoticeDelivery.READ || status == DebtorNoticeDelivery.REGISTERED;
    }

    private static int rank(DebtorNoticeDelivery s) {
        return switch (s) {
            case QUEUED -> 0;
            case SENT -> 1;
            case DELIVERED -> 2;
            case READ -> 3;
            case FAILED, BLOCKED -> 1;
            case REGISTERED -> 4;
        };
    }

    private static String truncate(String value) {
        return value == null ? null : value.length() > 500 ? value.substring(0, 500) : value;
    }

    public UUID getDisputeId() {
        return disputeId;
    }

    public DebtorNoticeChannel getChannel() {
        return channel;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public DebtorNoticeDelivery getStatus() {
        return status;
    }

    public String getFailureDetail() {
        return failureDetail;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public Integer getTemplateVersion() {
        return templateVersion;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public UUID getSentBy() {
        return sentBy;
    }

    public Instant getStatusAt() {
        return statusAt;
    }
}
