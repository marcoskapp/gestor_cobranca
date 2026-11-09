package br.com.gestorcobranca.complaint.domain.model;

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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * Reclamação recebida por canal externo (Procon, consumidor.gov.br, Bacen/ouvidoria, Reclame Aqui…) sobre uma
 * cobrança feita pela assessoria em nome de um credor. Sempre de um credor (escopo); devedor e caso opcionais, mas o
 * caso, se informado, precisa ser do devedor e do credor.
 * <p>
 * O resumo da reclamação e a resposta dada ao canal são texto com dado pessoal: ficam criptografados e saem da
 * listagem (só no detalhe). O protocolo externo é do canal (não identifica a pessoa sozinho).
 * <p>
 * Prazo de resposta ({@code dueDate}, data de negócio) fixado no registro: o informado pelo canal ou a regra do
 * canal; respondida até o prazo = "no prazo". {@code overdueNotifiedAt} marca o aviso único de prazo vencido.
 */
@Entity
@Table(name = "complaint", schema = "complaint")
public class Complaint extends TenantScopedEntity {

    public static final int SUMMARY_MAX = 4000;
    public static final int RESPONSE_MAX = 8000;
    public static final int PROTOCOL_MAX = 60;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "debtor_id")
    private UUID debtorId;

    @Column(name = "case_id")
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20, updatable = false)
    private ComplaintChannel channel;

    @Column(name = "external_protocol", length = PROTOCOL_MAX)
    private String externalProtocol;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject", nullable = false, length = 25)
    private ComplaintSubject subject;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "summary_encrypted", nullable = false, columnDefinition = "text")
    private String summary;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "deadline_source", nullable = false, length = 15)
    private DeadlineSource deadlineSource;

    /** A partir desta data a reclamação está "perto do prazo" (prazo menos o aviso do canal). */
    @Column(name = "warning_date", nullable = false)
    private LocalDate warningDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private ComplaintStatus status;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "response_encrypted", columnDefinition = "text")
    private String response;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(name = "answered_by")
    private UUID answeredBy;

    /** Respondida até o fim do dia do prazo (fuso de negócio). */
    @Column(name = "answered_on_time")
    private Boolean answeredOnTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 20)
    private ComplaintOutcome outcome;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Column(name = "overdue_notified_at")
    private Instant overdueNotifiedAt;

    @Column(name = "registered_by", updatable = false)
    private UUID registeredBy;

    protected Complaint() {
    }

    public Complaint(UUID creditorId, UUID debtorId, UUID caseId, ComplaintChannel channel, String externalProtocol,
                     ComplaintSubject subject, String summary, Instant receivedAt, LocalDate dueDate,
                     DeadlineSource deadlineSource, int warningDays, UUID registeredBy) {
        this.creditorId = Guard.requireNonNull(creditorId, "complaint.creditor_required", "Credor obrigatório");
        this.channel = Guard.requireNonNull(channel, "complaint.channel_required", "Canal obrigatório");
        this.subject = Guard.requireNonNull(subject, "complaint.subject_required", "Assunto obrigatório");
        Guard.require(caseId == null || debtorId != null, "complaint.case_requires_debtor",
                "Caso vinculado exige o devedor");
        this.debtorId = debtorId;
        this.caseId = caseId;
        this.externalProtocol = protocol(externalProtocol);
        this.summary = text(summary, SUMMARY_MAX, "complaint.summary_required",
                "Resumo da reclamação obrigatório (até " + SUMMARY_MAX + " caracteres)");
        this.receivedAt = Guard.requireNonNull(receivedAt, "complaint.received_required", "Data de recebimento obrigatória");
        this.dueDate = Guard.requireNonNull(dueDate, "complaint.due_required", "Prazo de resposta obrigatório");
        this.deadlineSource = deadlineSource == null ? DeadlineSource.DEFAULT : deadlineSource;
        this.warningDate = dueDate.minusDays(Math.max(0, warningDays));
        this.registeredBy = registeredBy;
        this.status = ComplaintStatus.RECEIVED;
    }

    /** Corrige vínculos e classificação enquanto a reclamação não foi encerrada. */
    public void reclassify(UUID debtorId, UUID caseId, String externalProtocol, ComplaintSubject subject) {
        Guard.requireState(status != ComplaintStatus.CLOSED, "complaint.closed", "Reclamação encerrada");
        Guard.require(caseId == null || debtorId != null, "complaint.case_requires_debtor",
                "Caso vinculado exige o devedor");
        // desvincular o devedor tiraria a reclamação do alcance da anonimização e dos direitos do titular
        Guard.require(this.debtorId == null || debtorId != null, "complaint.debtor_unlink_not_allowed",
                "Não é possível desvincular o devedor da reclamação; corrija para o devedor certo");
        this.debtorId = debtorId;
        this.caseId = caseId;
        this.externalProtocol = protocol(externalProtocol);
        this.subject = Guard.requireNonNull(subject, "complaint.subject_required", "Assunto obrigatório");
    }

    /** Novo prazo informado pelo canal (ex.: prorrogação concedida pelo Procon). */
    public LocalDate changeDueDate(LocalDate newDueDate, int warningDays) {
        Guard.requireState(status.awaitingAnswer(), "complaint.not_awaiting", "Reclamação já respondida");
        Guard.require(newDueDate != null, "complaint.due_required", "Prazo de resposta obrigatório");
        LocalDate previous = dueDate;
        this.dueDate = newDueDate;
        this.warningDate = newDueDate.minusDays(Math.max(0, warningDays));
        this.deadlineSource = DeadlineSource.INFORMED;
        // novo prazo: se vencer de novo, há novo aviso
        this.overdueNotifiedAt = null;
        return previous;
    }

    /** @return o responsável anterior */
    public UUID assign(UUID assignee, Instant now) {
        Guard.requireState(status != ComplaintStatus.CLOSED, "complaint.closed", "Reclamação encerrada");
        Guard.requireState(!Objects.equals(assignee, assigneeId), "complaint.same_assignee",
                assignee == null ? "A reclamação já está sem responsável" : "A reclamação já é deste responsável");
        UUID previous = assigneeId;
        this.assigneeId = assignee;
        this.assignedAt = assignee == null ? null : now;
        return previous;
    }

    public void start() {
        Guard.requireState(status == ComplaintStatus.RECEIVED, "complaint.not_received", "Reclamação já em tratamento");
        this.status = ComplaintStatus.IN_PROGRESS;
    }

    /** Registra a resposta dada ao canal; "no prazo" se respondida até o fim do dia do prazo. */
    public void answer(String responseText, Instant at, UUID by, ZoneId zone) {
        Guard.requireState(status.awaitingAnswer(), "complaint.not_awaiting", "Reclamação já respondida");
        Guard.require(at != null && !at.isBefore(receivedAt), "complaint.answer_before_received",
                "A resposta não pode ser anterior ao recebimento");
        this.response = text(responseText, RESPONSE_MAX, "complaint.response_required",
                "Resposta obrigatória (até " + RESPONSE_MAX + " caracteres)");
        this.status = ComplaintStatus.ANSWERED;
        this.answeredAt = at;
        this.answeredBy = by;
        this.answeredOnTime = !at.atZone(zone).toLocalDate().isAfter(dueDate);
    }

    public void close(ComplaintOutcome result, Instant now, UUID by) {
        Guard.requireState(status == ComplaintStatus.ANSWERED, "complaint.not_answered",
                "Registre a resposta dada ao canal antes de encerrar");
        this.outcome = Guard.requireNonNull(result, "complaint.outcome_required", "Resultado obrigatório");
        this.status = ComplaintStatus.CLOSED;
        this.closedAt = now;
        this.closedBy = by;
    }

    public boolean isOverdue(LocalDate today) {
        return status.awaitingAnswer() && dueDate.isBefore(today);
    }

    /** ON_TIME, DUE_SOON, OVERDUE (aguardando resposta) ou ANSWERED_ON_TIME / ANSWERED_LATE. */
    public String deadlineState(LocalDate today) {
        if (!status.awaitingAnswer()) {
            return Boolean.FALSE.equals(answeredOnTime) ? "ANSWERED_LATE" : "ANSWERED_ON_TIME";
        }
        if (dueDate.isBefore(today)) {
            return "OVERDUE";
        }
        return today.isBefore(warningDate) ? "ON_TIME" : "DUE_SOON";
    }

    /** @return true se este é o primeiro aviso de vencimento */
    public boolean markOverdueNotified(LocalDate today, Instant now) {
        if (!isOverdue(today) || overdueNotifiedAt != null) {
            return false;
        }
        this.overdueNotifiedAt = now;
        return true;
    }

    /** LGPD: o texto livre sai na anonimização do devedor (vínculos e prazos ficam para os indicadores). */
    public void anonymize() {
        this.summary = "[dados removidos — anonimização do titular]";
        if (response != null) {
            this.response = "[dados removidos — anonimização do titular]";
        }
        this.externalProtocol = null;
    }

    private static String protocol(String value) {
        String trimmed = value == null || value.isBlank() ? null : value.strip();
        Guard.require(trimmed == null || trimmed.length() <= PROTOCOL_MAX, "complaint.protocol_too_long",
                "Protocolo até " + PROTOCOL_MAX + " caracteres");
        return trimmed;
    }

    private static String text(String value, int max, String code, String message) {
        Guard.require(value != null && !value.isBlank() && value.strip().length() <= max, code, message);
        return value.strip();
    }

    public UUID getCreditorId() { return creditorId; }
    public UUID getDebtorId() { return debtorId; }
    public UUID getCaseId() { return caseId; }
    public ComplaintChannel getChannel() { return channel; }
    public String getExternalProtocol() { return externalProtocol; }
    public ComplaintSubject getSubject() { return subject; }
    public String getSummary() { return summary; }
    public Instant getReceivedAt() { return receivedAt; }
    public LocalDate getDueDate() { return dueDate; }
    public DeadlineSource getDeadlineSource() { return deadlineSource; }
    public LocalDate getWarningDate() { return warningDate; }
    public ComplaintStatus getStatus() { return status; }
    public UUID getAssigneeId() { return assigneeId; }
    public Instant getAssignedAt() { return assignedAt; }
    public String getResponse() { return response; }
    public Instant getAnsweredAt() { return answeredAt; }
    public UUID getAnsweredBy() { return answeredBy; }
    public Boolean getAnsweredOnTime() { return answeredOnTime; }
    public ComplaintOutcome getOutcome() { return outcome; }
    public Instant getClosedAt() { return closedAt; }
    public UUID getClosedBy() { return closedBy; }
    public Instant getOverdueNotifiedAt() { return overdueNotifiedAt; }
    public UUID getRegisteredBy() { return registeredBy; }
}
