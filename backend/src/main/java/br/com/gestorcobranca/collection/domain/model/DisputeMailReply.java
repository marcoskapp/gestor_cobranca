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
 * E-mail do credor recebido no endereço de resposta de um pedido da contestação ("resposta recebida por e-mail, a
 * confirmar"). Guarda o original (.eml, com SHA-256, para a cadeia de custódia), o texto já sanitizado, os veredictos
 * de autenticação e vírus e o desfecho. Nunca resolve nada sozinho: o analista confirma a posição do credor (o que
 * registra a resposta do credor e encerra o "aguardando o credor") ou descarta com motivo. Em quarentena, a gestão
 * libera (quando o motivo permite) ou descarta. O conteúdo recebido é imutável (gatilho no banco).
 */
@Entity
@Table(name = "dispute_mail_reply", schema = "collection")
public class DisputeMailReply extends TenantScopedEntity {

    public static final int TEXT_MAX = 50_000;
    public static final int SUBJECT_MAX = 500;
    public static final int NOTE_MAX = 2000;

    @Column(name = "dispute_id", nullable = false, updatable = false)
    private UUID disputeId;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "provider_message_id", nullable = false, length = 200, updatable = false)
    private String providerMessageId;

    @Column(name = "message_id_hash", length = 64, updatable = false)
    private String messageIdHash;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "sender_encrypted", nullable = false, length = 2000, updatable = false)
    private String sender;

    @Column(name = "sender_hash", nullable = false, length = 64, updatable = false)
    private String senderHash;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "subject_encrypted", length = 4000, updatable = false)
    private String subject;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "text_encrypted", length = 250_000, updatable = false)
    private String text;

    @Column(name = "text_truncated", nullable = false, updatable = false)
    private boolean textTruncated;

    @Column(name = "raw_key", nullable = false, length = 500, updatable = false)
    private String rawKey;

    @Column(name = "raw_sha256", nullable = false, length = 64, updatable = false)
    private String rawSha256;

    @Column(name = "raw_size", nullable = false, updatable = false)
    private long rawSize;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "spf_verdict", nullable = false, length = 20, updatable = false)
    private MailVerdict spf;

    @Enumerated(EnumType.STRING)
    @Column(name = "dkim_verdict", nullable = false, length = 20, updatable = false)
    private MailVerdict dkim;

    @Enumerated(EnumType.STRING)
    @Column(name = "dmarc_verdict", nullable = false, length = 20, updatable = false)
    private MailVerdict dmarc;

    @Enumerated(EnumType.STRING)
    @Column(name = "virus_verdict", nullable = false, length = 20, updatable = false)
    private MailVerdict virus;

    @Enumerated(EnumType.STRING)
    @Column(name = "spam_verdict", nullable = false, length = 20, updatable = false)
    private MailVerdict spam;

    @Column(name = "attachments", nullable = false)
    private int attachments;

    @Column(name = "attachments_rejected", nullable = false)
    private int attachmentsRejected;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 25)
    private MailReplyStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "quarantine_reason", length = 30)
    private MailQuarantineReason quarantineReason;

    @Column(name = "released_by")
    private UUID releasedBy;

    @Column(name = "released_by_name", length = 200)
    private String releasedByName;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "release_note_encrypted", length = 24000)
    private String releaseNote;

    @Column(name = "response_id")
    private UUID responseId;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_by_name", length = 200)
    private String decidedByName;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "decision_note_encrypted", length = 24000)
    private String decisionNote;

    protected DisputeMailReply() {
    }

    /**
     * @param quarantine motivo da quarentena (nulo = passou nas verificações e aguarda o analista)
     */
    public DisputeMailReply(DisputeCreditorRequest request, String providerMessageId, String messageIdHash,
                            String sender, String senderHash, String subject, String text, boolean textTruncated,
                            RawMessage raw, Instant receivedAt, Verdicts verdicts, int attachments,
                            MailQuarantineReason quarantine) {
        Guard.require(providerMessageId != null && !providerMessageId.isBlank(), "dispute_mail.message_id_required",
                "Identificador do e-mail obrigatório");
        Guard.require(sender != null && senderHash != null, "dispute_mail.sender_required", "Remetente obrigatório");
        Guard.require(raw != null && raw.key() != null && raw.sha256() != null, "dispute_mail.raw_required",
                "Original do e-mail obrigatório");
        Guard.require(receivedAt != null && verdicts != null, "dispute_mail.invalid", "E-mail inválido");
        this.disputeId = request.getDisputeId();
        this.requestId = request.getId();
        this.creditorId = request.getCreditorId();
        this.providerMessageId = cut(providerMessageId, 200);
        this.messageIdHash = messageIdHash;
        this.sender = cut(sender, 320);
        this.senderHash = senderHash;
        this.subject = subject == null ? null : cut(subject, SUBJECT_MAX);
        this.text = text == null ? null : cut(text, TEXT_MAX);
        this.textTruncated = textTruncated || (text != null && text.length() > TEXT_MAX);
        this.rawKey = raw.key();
        this.rawSha256 = raw.sha256();
        this.rawSize = raw.size();
        this.receivedAt = receivedAt;
        this.spf = verdicts.spf();
        this.dkim = verdicts.dkim();
        this.dmarc = verdicts.dmarc();
        this.virus = verdicts.virus();
        this.spam = verdicts.spam();
        this.attachments = Math.max(0, attachments);
        this.status = quarantine == null ? MailReplyStatus.PENDING_CONFIRMATION : MailReplyStatus.QUARANTINED;
        this.quarantineReason = quarantine;
    }

    /** Anexo recusado (tipo fora da lista ou acima do tamanho): fica só no original. */
    public void rejectAttachment() {
        Guard.require(attachmentsRejected < attachments, "dispute_mail.attachment_count", "Contagem de anexos inválida");
        attachmentsRejected++;
    }

    /** A gestão conferiu o original e libera o e-mail para o analista (só motivos liberáveis). */
    public void release(UUID actorId, String actorName, String note, Instant now) {
        Guard.requireState(status == MailReplyStatus.QUARANTINED, "dispute_mail.not_quarantined",
                "O e-mail não está em quarentena");
        Guard.requireState(quarantineReason.releasable(), "dispute_mail.not_releasable",
                "E-mail com falha de autenticação, vírus, ilegível ou de contestação encerrada não é liberado");
        Guard.require(note != null && !note.isBlank() && note.trim().length() <= NOTE_MAX, "dispute_mail.note_required",
                "Informe por que o e-mail foi liberado (até %d caracteres)".formatted(NOTE_MAX));
        this.status = MailReplyStatus.PENDING_CONFIRMATION;
        this.releasedBy = actorId;
        this.releasedByName = actorName;
        this.releasedAt = now;
        this.releaseNote = note.trim();
    }

    /** O analista confirmou a posição do credor: virou a resposta informada. */
    public void confirm(UUID responseId, UUID actorId, String actorName, Instant now) {
        Guard.requireState(status == MailReplyStatus.PENDING_CONFIRMATION, "dispute_mail.not_pending",
                "O e-mail não aguarda confirmação");
        Guard.require(responseId != null, "dispute_mail.response_required", "Resposta obrigatória");
        this.status = MailReplyStatus.CONFIRMED;
        this.responseId = responseId;
        this.decidedBy = actorId;
        this.decidedByName = actorName;
        this.decidedAt = now;
    }

    /** Descartado com motivo (sem nova posição do credor, duplicado, quarentena não liberável…). */
    public void dismiss(UUID actorId, String actorName, String note, Instant now) {
        Guard.requireState(isOpen(), "dispute_mail.closed", "E-mail já decidido");
        Guard.require(note != null && !note.isBlank() && note.trim().length() <= NOTE_MAX, "dispute_mail.note_required",
                "Informe o motivo do descarte (até %d caracteres)".formatted(NOTE_MAX));
        this.status = MailReplyStatus.DISMISSED;
        this.decidedBy = actorId;
        this.decidedByName = actorName;
        this.decidedAt = now;
        this.decisionNote = note.trim();
    }

    public boolean isOpen() {
        return status == MailReplyStatus.PENDING_CONFIRMATION || status == MailReplyStatus.QUARANTINED;
    }

    private static String cut(String value, int max) {
        String v = value.strip();
        return v.length() > max ? v.substring(0, max) : v;
    }

    /** Original guardado no armazenamento (categoria INBOUND_MAIL). */
    public record RawMessage(String key, String sha256, long size) {
    }

    public record Verdicts(MailVerdict spf, MailVerdict dkim, MailVerdict dmarc, MailVerdict virus, MailVerdict spam) {

        public Verdicts {
            spf = spf == null ? MailVerdict.DISABLED : spf;
            dkim = dkim == null ? MailVerdict.DISABLED : dkim;
            dmarc = dmarc == null ? MailVerdict.DISABLED : dmarc;
            virus = virus == null ? MailVerdict.DISABLED : virus;
            spam = spam == null ? MailVerdict.DISABLED : spam;
        }
    }

    public UUID getDisputeId() {
        return disputeId;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public String getSender() {
        return sender;
    }

    public String getSenderHash() {
        return senderHash;
    }

    public String getSubject() {
        return subject;
    }

    public String getText() {
        return text;
    }

    public boolean isTextTruncated() {
        return textTruncated;
    }

    public String getRawKey() {
        return rawKey;
    }

    public String getRawSha256() {
        return rawSha256;
    }

    public long getRawSize() {
        return rawSize;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Verdicts getVerdicts() {
        return new Verdicts(spf, dkim, dmarc, virus, spam);
    }

    public int getAttachments() {
        return attachments;
    }

    public int getAttachmentsRejected() {
        return attachmentsRejected;
    }

    public MailReplyStatus getStatus() {
        return status;
    }

    public MailQuarantineReason getQuarantineReason() {
        return quarantineReason;
    }

    public UUID getReleasedBy() {
        return releasedBy;
    }

    public String getReleasedByName() {
        return releasedByName;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public String getReleaseNote() {
        return releaseNote;
    }

    public UUID getResponseId() {
        return responseId;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public String getDecidedByName() {
        return decidedByName;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionNote() {
        return decisionNote;
    }
}
