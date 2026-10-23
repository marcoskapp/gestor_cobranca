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
 * Rascunho ou sugestão do assistente para o operador (modos sombra e copiloto): nada é enviado sem
 * a decisão de um humano, que envia (com ou sem edição) ou descarta.
 */
@Entity
@Table(name = "conversation_draft", schema = "communication")
public class ConversationDraft extends TenantScopedEntity {

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20, updatable = false)
    private DraftMode mode;

    @Column(name = "author_type", nullable = false, length = 20, updatable = false)
    private String authorType;

    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "content_encrypted", nullable = false, length = 100000)
    private String content;

    @Column(name = "rationale", length = 500, updatable = false)
    private String rationale;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DraftStatus status;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "edited", nullable = false)
    private boolean edited;

    @Column(name = "message_id")
    private UUID messageId;

    protected ConversationDraft() {
    }

    /** @param rationale justificativa curta para o operador, sem dados pessoais (ex.: "oferta nível 2") */
    public ConversationDraft(Conversation conversation, DraftMode mode, String authorType, UUID authorId, String content,
                             String rationale) {
        Guard.requireState(conversation.isOpen(), "conversation.closed", "Conversa encerrada");
        Guard.require(mode != null, "draft.mode_required", "Modo obrigatório");
        Guard.require(content != null && !content.isBlank() && content.length() <= Message.CONTENT_MAX,
                "draft.content_invalid", "Conteúdo do rascunho obrigatório (até %d caracteres)".formatted(Message.CONTENT_MAX));
        Guard.require(rationale == null || rationale.length() <= 500, "draft.rationale_too_long",
                "Justificativa até 500 caracteres");
        this.conversationId = conversation.getId();
        this.mode = mode;
        this.authorType = authorType;
        this.authorId = authorId;
        this.content = content;
        this.rationale = rationale == null || rationale.isBlank() ? null : rationale.trim();
        this.status = DraftStatus.PENDING;
    }

    /** O operador envia o rascunho; {@code finalContent} diferente do original marca edição. */
    public void sent(String finalContent, UUID messageId, UUID by, Instant now) {
        requirePending();
        this.edited = finalContent != null && !finalContent.equals(content);
        if (edited) {
            this.content = finalContent;
        }
        this.messageId = messageId;
        this.status = DraftStatus.SENT;
        this.decidedBy = by;
        this.decidedAt = now;
    }

    public void discard(UUID by, Instant now) {
        requirePending();
        this.status = DraftStatus.DISCARDED;
        this.decidedBy = by;
        this.decidedAt = now;
    }

    public void expire(Instant now) {
        if (status == DraftStatus.PENDING) {
            this.status = DraftStatus.EXPIRED;
            this.decidedAt = now;
        }
    }

    private void requirePending() {
        Guard.requireState(status == DraftStatus.PENDING, "draft.not_pending", "Rascunho já decidido");
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public DraftMode getMode() {
        return mode;
    }

    public String getAuthorType() {
        return authorType;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public String getContent() {
        return content;
    }

    public String getRationale() {
        return rationale;
    }

    public DraftStatus getStatus() {
        return status;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public boolean isEdited() {
        return edited;
    }

    public UUID getMessageId() {
        return messageId;
    }
}
