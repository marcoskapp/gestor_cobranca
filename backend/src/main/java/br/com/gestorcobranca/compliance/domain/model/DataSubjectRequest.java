package br.com.gestorcobranca.compliance.domain.model;

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
 * Solicitação de titular de dados (LGPD art. 18). Nome, documento, contato e descrição do
 * pedido são dados pessoais do requerente e ficam criptografados; o documento tem blind index
 * para localizar solicitações do mesmo titular. O devedor só é vinculado quando a identidade é
 * confirmada.
 * <p>
 * Ciclo de vida em {@link RequestStatus}; cada transição é validada aqui e auditada pelo caso
 * de uso. O prazo ({@code dueAt}) é fixado no recebimento; uma solicitação aberta após o prazo é
 * marcada como vencida (alerta), sem alterar o status.
 */
@Entity
@Table(name = "data_subject_request", schema = "compliance")
public class DataSubjectRequest extends TenantScopedEntity {

    /** Finalidade do blind index do documento do requerente. */
    public static final String DOCUMENT_HASH_PURPOSE = "data_subject_request.document";

    private static final int SUMMARY_MAX = 4000;
    private static final int DETAILS_MAX = 4000;

    @Column(name = "debtor_id")
    private UUID debtorId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "requester_name_encrypted", nullable = false, length = 2048)
    private String requesterName;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "document_encrypted", length = 512)
    private String document;

    @Column(name = "document_hash", length = 64)
    private String documentHash;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "requester_contact_encrypted", length = 2048)
    private String requesterContact;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "details_encrypted", length = 8192)
    private String details;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20, updatable = false)
    private RequestChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40, updatable = false)
    private RequestType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RequestStatus status;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "due_at", nullable = false, updatable = false)
    private Instant dueAt;

    @Column(name = "overdue", nullable = false)
    private boolean overdue;

    @Column(name = "overdue_at")
    private Instant overdueAt;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "identity_verified_at")
    private Instant identityVerifiedAt;

    @Column(name = "identity_verified_by")
    private UUID identityVerifiedBy;

    /** Quando a ação automática do tipo (exportação, anonimização, restrição) foi executada. */
    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "response_summary", length = SUMMARY_MAX)
    private String responseSummary;

    @Column(name = "result_file_key", length = 512)
    private String resultFileKey;

    @Column(name = "result_sha256", length = 64)
    private String resultSha256;

    protected DataSubjectRequest() {
    }

    /**
     * @param document     documento normalizado (somente dígitos), opcional
     * @param documentHash blind index do documento (finalidade {@link #DOCUMENT_HASH_PURPOSE})
     * @param dueAt        prazo de atendimento calculado no recebimento
     */
    public DataSubjectRequest(RequestType type, RequestChannel channel, String requesterName, String document,
                              String documentHash, String requesterContact, String details, Instant receivedAt,
                              Instant dueAt) {
        Guard.require(type != null, "dsr.type_required", "Tipo da solicitação obrigatório");
        Guard.require(channel != null, "dsr.channel_required", "Canal de recebimento obrigatório");
        Guard.require(requesterName != null && !requesterName.isBlank() && requesterName.trim().length() <= 200,
                "dsr.requester_name_invalid", "Nome do requerente obrigatório (até 200 caracteres)");
        Guard.require((document == null) == (documentHash == null), "dsr.document_hash_required",
                "Índice do documento obrigatório");
        Guard.require(requesterContact == null || requesterContact.length() <= 254, "dsr.contact_too_long",
                "Contato do requerente muito longo");
        Guard.require(details == null || details.length() <= DETAILS_MAX, "dsr.details_too_long",
                "Descrição do pedido muito longa");
        Guard.require(receivedAt != null && dueAt != null && !dueAt.isBefore(receivedAt), "dsr.due_invalid",
                "Prazo de atendimento inválido");
        this.type = type;
        this.channel = channel;
        this.requesterName = requesterName.trim();
        this.document = document;
        this.documentHash = documentHash;
        this.requesterContact = blankToNull(requesterContact);
        this.details = blankToNull(details);
        this.receivedAt = receivedAt;
        this.dueAt = dueAt;
        this.status = RequestStatus.RECEIVED;
    }

    /**
     * Confirma a identidade do requerente e vincula o titular localizado na base. Tipos cujo
     * atendimento age sobre os dados exigem o devedor; confirmação e informações podem ser
     * respondidas sem ele (ex.: "não tratamos dados seus").
     */
    public void verifyIdentity(UUID debtorId, UUID actorId, Instant now) {
        moveTo(RequestStatus.IDENTITY_VERIFIED);
        Guard.require(debtorId != null || !type.requiresDebtor(), "dsr.debtor_required",
                "Localize o titular na base antes de confirmar a identidade para este tipo de solicitação");
        this.debtorId = debtorId;
        this.identityVerifiedAt = now;
        this.identityVerifiedBy = actorId;
    }

    public void start(UUID assignee) {
        moveTo(RequestStatus.IN_PROGRESS);
        if (assignee != null) {
            this.assignedTo = assignee;
        }
    }

    public void assign(UUID assignee) {
        Guard.requireState(!status.isFinal(), "dsr.closed", "Solicitação encerrada");
        Guard.require(assignee != null, "dsr.assignee_required", "Responsável obrigatório");
        this.assignedTo = assignee;
    }

    /** Arquivo com os dados do titular (acesso/portabilidade). */
    public void attachResult(String fileKey, String sha256, Instant now) {
        Guard.requireState(type.producesFile(), "dsr.result_not_applicable", "Este tipo de solicitação não gera arquivo");
        Guard.require(fileKey != null && sha256 != null, "dsr.result_invalid", "Arquivo de resultado inválido");
        markExecuted(now);
        this.resultFileKey = fileKey;
        this.resultSha256 = sha256;
    }

    /** Exige atendimento em andamento, tipo executável e ação ainda não executada. */
    public void requireExecutable() {
        Guard.requireState(status == RequestStatus.IN_PROGRESS, "dsr.not_in_progress", "Solicitação não está em atendimento");
        Guard.requireState(type.isExecutable(), "dsr.not_executable",
                "Solicitação do tipo %s é atendida manualmente".formatted(type));
        Guard.requireState(executedAt == null, "dsr.already_executed", "Ação da solicitação já executada");
    }

    public void markExecuted(Instant now) {
        requireExecutable();
        this.executedAt = now;
    }

    public void complete(String summary, Instant now) {
        Guard.requireState(!type.isExecutable() || executedAt != null, "dsr.execution_required",
                "Execute a ação da solicitação antes de concluir");
        Guard.requireState(!type.producesFile() || resultFileKey != null, "dsr.result_required",
                "Gere o arquivo de dados do titular antes de concluir");
        moveTo(RequestStatus.COMPLETED);
        close(summary, now);
    }

    /** Recusa fundamentada: o resumo deve conter a justificativa apresentada ao titular. */
    public void reject(String justification, Instant now) {
        moveTo(RequestStatus.REJECTED);
        close(justification, now);
    }

    /** @return true se a solicitação acabou de ser marcada como vencida */
    public boolean markOverdueIfLate(Instant now) {
        if (overdue || status.isFinal() || now.isBefore(dueAt)) {
            return false;
        }
        this.overdue = true;
        this.overdueAt = now;
        return true;
    }

    private void close(String summary, Instant now) {
        Guard.require(summary != null && !summary.isBlank(), "dsr.response_required", "Resposta ao titular obrigatória");
        String trimmed = summary.trim();
        Guard.require(trimmed.length() <= SUMMARY_MAX, "dsr.response_too_long",
                "Resposta deve ter até %d caracteres".formatted(SUMMARY_MAX));
        this.responseSummary = trimmed;
        this.completedAt = now;
    }

    private void moveTo(RequestStatus target) {
        Guard.requireState(status.canTransitionTo(target), "dsr.invalid_transition",
                "Solicitação com status %s não pode passar para %s".formatted(status, target));
        this.status = target;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public String getRequesterName() {
        return requesterName;
    }

    public String getDocument() {
        return document;
    }

    public String getDocumentHash() {
        return documentHash;
    }

    public String getRequesterContact() {
        return requesterContact;
    }

    public String getDetails() {
        return details;
    }

    public RequestChannel getChannel() {
        return channel;
    }

    public RequestType getType() {
        return type;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public boolean isOverdue() {
        return overdue;
    }

    public Instant getOverdueAt() {
        return overdueAt;
    }

    public UUID getAssignedTo() {
        return assignedTo;
    }

    public Instant getIdentityVerifiedAt() {
        return identityVerifiedAt;
    }

    public UUID getIdentityVerifiedBy() {
        return identityVerifiedBy;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getResponseSummary() {
        return responseSummary;
    }

    public String getResultFileKey() {
        return resultFileKey;
    }

    public String getResultSha256() {
        return resultSha256;
    }
}
