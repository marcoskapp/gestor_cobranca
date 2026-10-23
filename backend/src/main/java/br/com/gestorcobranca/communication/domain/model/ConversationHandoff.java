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
 * Registro imutável de uma mudança de dono da conversa: de, para, natureza, motivo, resumo (texto
 * para o operador, criptografado), perfil exigido e quem fez. Transferências do assistente para
 * humanos sempre levam motivo e resumo.
 */
@Entity
@Table(name = "conversation_handoff", schema = "communication")
public class ConversationHandoff extends TenantScopedEntity {

    public static final int SUMMARY_MAX = 2000;
    public static final java.util.regex.Pattern REASON = java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{1,59}");

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(name = "case_id", updatable = false)
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_owner_type", nullable = false, length = 20, updatable = false)
    private OwnerType fromOwnerType;

    @Column(name = "from_user_id", updatable = false)
    private UUID fromUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_owner_type", nullable = false, length = 20, updatable = false)
    private OwnerType toOwnerType;

    @Column(name = "to_user_id", updatable = false)
    private UUID toUserId;

    @Column(name = "to_team_id", updatable = false)
    private UUID toTeamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30, updatable = false)
    private HandoffKind kind;

    @Column(name = "reason_code", nullable = false, length = 60, updatable = false)
    private String reasonCode;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "summary_encrypted", length = 12000, updatable = false)
    private String summary;

    @Column(name = "required_profile", length = 40, updatable = false)
    private String requiredProfile;

    @Column(name = "actor_type", nullable = false, length = 20, updatable = false)
    private String actorType;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Column(name = "reference_type", length = 40, updatable = false)
    private String referenceType;

    @Column(name = "reference_id", updatable = false)
    private UUID referenceId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected ConversationHandoff() {
    }

    public ConversationHandoff(Conversation conversation, Conversation.Owner from, Conversation.Owner to, HandoffKind kind,
                               String reasonCode, String summary, String actorType, UUID actorId, String referenceType,
                               UUID referenceId, Instant occurredAt) {
        Guard.require(kind != null, "handoff.kind_required", "Natureza da transferência obrigatória");
        Guard.require(reasonCode != null && REASON.matcher(reasonCode).matches(), "handoff.reason_invalid",
                "Motivo em código (maiúsculas, números e _)");
        Guard.require(summary == null || summary.length() <= SUMMARY_MAX, "handoff.summary_too_long",
                "Resumo até %d caracteres".formatted(SUMMARY_MAX));
        if (kind == HandoffKind.ESCALATION) {
            Guard.require(summary != null && !summary.isBlank(), "handoff.summary_required",
                    "Transferência para humano exige resumo do atendimento");
        }
        Guard.require(referenceType == null || referenceType.length() <= 40, "handoff.reference_invalid",
                "Tipo de referência até 40 caracteres");
        this.conversationId = conversation.getId();
        this.caseId = conversation.getCaseId();
        this.fromOwnerType = from.type();
        this.fromUserId = from.userId();
        this.toOwnerType = to.type();
        this.toUserId = to.userId();
        this.toTeamId = to.teamId();
        this.kind = kind;
        this.reasonCode = reasonCode;
        this.summary = summary == null || summary.isBlank() ? null : summary.trim();
        this.requiredProfile = to.requiredProfile();
        this.actorType = actorType;
        this.actorId = actorId;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.occurredAt = occurredAt;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public OwnerType getFromOwnerType() {
        return fromOwnerType;
    }

    public UUID getFromUserId() {
        return fromUserId;
    }

    public OwnerType getToOwnerType() {
        return toOwnerType;
    }

    public UUID getToUserId() {
        return toUserId;
    }

    public UUID getToTeamId() {
        return toTeamId;
    }

    public HandoffKind getKind() {
        return kind;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getSummary() {
        return summary;
    }

    public String getRequiredProfile() {
        return requiredProfile;
    }

    public String getActorType() {
        return actorType;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
