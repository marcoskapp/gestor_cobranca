package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pedido formal da assessoria ao credor dentro da contestação (por e-mail, aos contatos de contestação do cadastro do
 * credor): texto a partir do modelo do tipo, editado pelo analista, títulos citados, evidências compartilhadas por
 * link seguro com expiração e prazo pedido ao credor. Assunto e texto ficam criptografados.
 */
@Entity
@Table(name = "dispute_creditor_request", schema = "collection")
public class DisputeCreditorRequest extends TenantScopedEntity {

    public static final int BODY_MAX = 8000;
    public static final int MAX_RECIPIENTS = 10;

    @Column(name = "dispute_id", nullable = false, updatable = false)
    private UUID disputeId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "sequence_number", nullable = false, updatable = false)
    private int sequenceNumber;

    @Column(name = "template_key", nullable = false, length = 30, updatable = false)
    private String templateKey;

    @Column(name = "template_version", nullable = false, updatable = false)
    private int templateVersion;

    @Column(name = "to_recipients", nullable = false, length = 2000, updatable = false)
    private String toRecipients;

    @Column(name = "cc_recipients", length = 2000, updatable = false)
    private String ccRecipients;

    @Column(name = "reply_to", length = 200, updatable = false)
    private String replyTo;

    /**
     * Endereço de resposta próprio do pedido (contestacao+token@subdomínio de entrada), quando a leitura das respostas
     * por e-mail está ligada; criptografado (o token só existe aqui e, como hash, na rota).
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "reply_address_encrypted", length = 2000, updatable = false)
    private String replyAddress;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "subject_encrypted", nullable = false, length = 2000, updatable = false)
    private String subject;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "body_encrypted", nullable = false, length = 48000, updatable = false)
    private String body;

    @Column(name = "requested_due_date", nullable = false, updatable = false)
    private LocalDate requestedDueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CreditorRequestStatus status;

    @Column(name = "link_expires_at", updatable = false)
    private Instant linkExpiresAt;

    @Column(name = "link_downloads", nullable = false)
    private int linkDownloads;

    @Column(name = "overdue", nullable = false)
    private boolean overdue;

    @Column(name = "reminder_queued_at")
    private Instant reminderQueuedAt;

    @Column(name = "overdue_notice_queued_at")
    private Instant overdueNoticeQueuedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "dispute_creditor_request_title", schema = "collection",
            joinColumns = @JoinColumn(name = "request_id"))
    @Column(name = "title_id", nullable = false)
    private Set<UUID> titleIds = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "dispute_creditor_request_evidence", schema = "collection",
            joinColumns = @JoinColumn(name = "request_id"))
    @Column(name = "evidence_id", nullable = false)
    private Set<UUID> evidenceIds = new HashSet<>();

    protected DisputeCreditorRequest() {
    }

    /**
     * @param linkExpiresAt validade do link das evidências (nulo = sem evidências compartilhadas)
     */
    public DisputeCreditorRequest(Dispute dispute, int sequenceNumber, String templateKey, int templateVersion,
                                  List<String> to, List<String> cc, String replyTo, String subject, String body,
                                  LocalDate requestedDueDate, Collection<UUID> titleIds, Collection<UUID> evidenceIds,
                                  Instant linkExpiresAt, UUID requestedBy) {
        Guard.requireState(dispute.getStatus().isOpen(), "dispute.not_open", "Contestação já encerrada");
        Guard.require(to != null && !to.isEmpty(), "dispute_request.no_recipient",
                "O credor não tem contato de contestação cadastrado");
        Guard.require(to.size() + (cc == null ? 0 : cc.size()) <= MAX_RECIPIENTS, "dispute_request.too_many_recipients",
                "Até %d destinatários por pedido".formatted(MAX_RECIPIENTS));
        Guard.require(subject != null && !subject.isBlank() && subject.trim().length() <= DisputeTemplate.SUBJECT_MAX,
                "dispute_request.subject_required", "Assunto obrigatório (até %d caracteres)"
                        .formatted(DisputeTemplate.SUBJECT_MAX));
        Guard.require(body != null && !body.isBlank() && body.trim().length() <= BODY_MAX, "dispute_request.body_required",
                "Texto obrigatório (até %d caracteres)".formatted(BODY_MAX));
        Guard.require(requestedDueDate != null, "dispute_request.due_required", "Prazo pedido ao credor obrigatório");
        Guard.require(evidenceIds == null || evidenceIds.isEmpty() || linkExpiresAt != null,
                "dispute_request.link_required", "Evidências são compartilhadas por link com validade");
        this.disputeId = dispute.getId();
        this.creditorId = dispute.getCreditorId();
        this.sequenceNumber = sequenceNumber;
        this.templateKey = templateKey;
        this.templateVersion = templateVersion;
        this.toRecipients = String.join(",", to);
        this.ccRecipients = cc == null || cc.isEmpty() ? null : String.join(",", cc);
        this.replyTo = replyTo;
        this.subject = subject.trim();
        this.body = body.trim();
        this.requestedDueDate = requestedDueDate;
        this.status = CreditorRequestStatus.QUEUED;
        if (titleIds != null) {
            this.titleIds.addAll(titleIds);
        }
        if (evidenceIds != null) {
            this.evidenceIds.addAll(evidenceIds);
        }
        this.linkExpiresAt = this.evidenceIds.isEmpty() ? null : linkExpiresAt;
        this.requestedBy = requestedBy;
    }

    /** Endereço de resposta próprio do pedido (só antes de gravar). */
    public void useReplyAddress(String address) {
        Guard.requireState(getVersion() == null && replyAddress == null, "dispute_request.reply_address_set",
                "Endereço de resposta já definido");
        Guard.require(address != null && address.contains("@") && address.length() <= 254,
                "dispute_request.reply_address_invalid", "Endereço de resposta inválido");
        this.replyAddress = address;
    }

    /** O e-mail do pedido foi aceito pelo servidor de e-mail. */
    public void markSent(Instant now) {
        if (status == CreditorRequestStatus.QUEUED || status == CreditorRequestStatus.FAILED) {
            status = CreditorRequestStatus.SENT;
            sentAt = now;
        }
    }

    public void markFailed() {
        if (status == CreditorRequestStatus.QUEUED) {
            status = CreditorRequestStatus.FAILED;
        }
    }

    /** @return true se passou a respondido agora */
    public boolean markAnswered(Instant now) {
        if (status == CreditorRequestStatus.SENT || status == CreditorRequestStatus.QUEUED
                || status == CreditorRequestStatus.FAILED) {
            status = CreditorRequestStatus.ANSWERED;
            answeredAt = now;
            return true;
        }
        return false;
    }

    /** A contestação foi encerrada sem resposta deste pedido. */
    public boolean cancel() {
        if (isAwaiting() || status == CreditorRequestStatus.FAILED) {
            status = CreditorRequestStatus.CANCELLED;
            return true;
        }
        return false;
    }

    public boolean isAwaiting() {
        return status == CreditorRequestStatus.SENT || status == CreditorRequestStatus.QUEUED;
    }

    /** Lembrete enfileirado (uma vez). @return true se ainda não havia */
    public boolean queueReminder(Instant now) {
        if (status != CreditorRequestStatus.SENT || reminderQueuedAt != null) {
            return false;
        }
        reminderQueuedAt = now;
        return true;
    }

    /** Prazo do credor vencido sem resposta: sinaliza e enfileira a cobrança do retorno (uma vez). */
    public boolean flagOverdue(LocalDate today, Instant now) {
        if (status != CreditorRequestStatus.SENT || overdue || !today.isAfter(requestedDueDate)) {
            return false;
        }
        overdue = true;
        overdueNoticeQueuedAt = now;
        return true;
    }

    public boolean linkUsableAt(Instant now) {
        return linkExpiresAt != null && now.isBefore(linkExpiresAt) && !evidenceIds.isEmpty()
                && status != CreditorRequestStatus.CANCELLED;
    }

    public void countDownload() {
        linkDownloads++;
    }

    public UUID getDisputeId() {
        return disputeId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public int getTemplateVersion() {
        return templateVersion;
    }

    public List<String> getTo() {
        return Arrays.stream(toRecipients.split(",")).filter(s -> !s.isBlank()).toList();
    }

    public List<String> getCc() {
        return ccRecipients == null ? List.of() : Arrays.stream(ccRecipients.split(",")).filter(s -> !s.isBlank()).toList();
    }

    public String getReplyTo() {
        return replyTo;
    }

    /** Endereço de resposta do pedido na caixa de entrada da plataforma (nulo = respostas só para o responsável). */
    public String getReplyAddress() {
        return replyAddress;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public LocalDate getRequestedDueDate() {
        return requestedDueDate;
    }

    public CreditorRequestStatus getStatus() {
        return status;
    }

    public Instant getLinkExpiresAt() {
        return linkExpiresAt;
    }

    public int getLinkDownloads() {
        return linkDownloads;
    }

    public boolean isOverdue() {
        return overdue;
    }

    public Instant getReminderQueuedAt() {
        return reminderQueuedAt;
    }

    public Instant getOverdueNoticeQueuedAt() {
        return overdueNoticeQueuedAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getAnsweredAt() {
        return answeredAt;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public Set<UUID> getTitleIds() {
        return Set.copyOf(titleIds);
    }

    public Set<UUID> getEvidenceIds() {
        return Set.copyOf(evidenceIds);
    }
}
