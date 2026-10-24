package br.com.gestorcobranca.portal.domain.model;

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
 * Pedido do devedor que foge do portal (falar com atendente, contestação, nenhuma condição cabe).
 * Encaminhado à fila humana do communication: uma mensagem de confirmação abre/reutiliza a conversa
 * do devedor e um {@code HandoffRequested} a transfere para a fila com o motivo e um resumo sem dados
 * pessoais. A observação livre do devedor fica só aqui, criptografada.
 */
@Entity
@Table(name = "service_request", schema = "portal")
public class ServiceRequest extends TenantScopedEntity {

    public static final int MAX_NOTE = 500;

    @Column(name = "configuration_id", nullable = false, updatable = false)
    private UUID configurationId;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", updatable = false)
    private UUID portfolioId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "protocol", nullable = false, length = 20, updatable = false)
    private String protocol;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 20, updatable = false)
    private ServiceRequestReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_channel", nullable = false, length = 20, updatable = false)
    private PortalChannel preferredChannel;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "note_encrypted", columnDefinition = "text")
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private ServiceRequestStatus status;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "failure_code", length = 60)
    private String failureCode;

    @Column(name = "forwarded_at")
    private Instant forwardedAt;

    protected ServiceRequest() {
    }

    public ServiceRequest(UUID configurationId, UUID sessionId, UUID creditorId, UUID portfolioId, UUID debtorId,
                          String protocol, ServiceRequestReason reason, PortalChannel preferredChannel, String note) {
        this.configurationId = configurationId;
        this.sessionId = sessionId;
        this.creditorId = creditorId;
        this.portfolioId = portfolioId;
        this.debtorId = Guard.requireNonNull(debtorId, "portal.debtor_required", "Devedor obrigatório");
        this.protocol = protocol;
        this.reason = Guard.requireNonNull(reason, "portal.request_reason_required", "Motivo obrigatório");
        this.preferredChannel = Guard.requireNonNull(preferredChannel, "portal.channel_required", "Canal obrigatório");
        String trimmed = note == null || note.isBlank() ? null : note.strip();
        Guard.require(trimmed == null || trimmed.length() <= MAX_NOTE, "portal.request_note_too_long",
                "Mensagem até " + MAX_NOTE + " caracteres");
        Guard.require(reason != ServiceRequestReason.OTHER || trimmed != null, "portal.request_note_required",
                "Conte em poucas palavras o que você precisa");
        this.note = trimmed;
        this.status = ServiceRequestStatus.OPEN;
    }

    public void forwarded(UUID conversationId, UUID messageId, Instant now) {
        this.status = ServiceRequestStatus.FORWARDED;
        this.conversationId = conversationId;
        this.messageId = messageId;
        this.failureCode = null;
        this.forwardedAt = now;
    }

    public void undelivered(String failureCode, Instant now) {
        this.status = ServiceRequestStatus.UNDELIVERED;
        this.failureCode = failureCode == null ? null : failureCode.length() > 60 ? failureCode.substring(0, 60) : failureCode;
        this.forwardedAt = now;
    }

    /** LGPD: a observação livre do devedor é apagada na anonimização. */
    public void anonymize() {
        this.note = null;
    }

    public UUID getConfigurationId() { return configurationId; }
    public UUID getSessionId() { return sessionId; }
    public UUID getCreditorId() { return creditorId; }
    public UUID getPortfolioId() { return portfolioId; }
    public UUID getDebtorId() { return debtorId; }
    public String getProtocol() { return protocol; }
    public ServiceRequestReason getReason() { return reason; }
    public PortalChannel getPreferredChannel() { return preferredChannel; }
    public String getNote() { return note; }
    public ServiceRequestStatus getStatus() { return status; }
    public UUID getConversationId() { return conversationId; }
    public UUID getMessageId() { return messageId; }
    public String getFailureCode() { return failureCode; }
    public Instant getForwardedAt() { return forwardedAt; }
}
