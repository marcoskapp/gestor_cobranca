package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
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
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Contestação (F21) de um ou mais títulos do caso, aberta pelo operador a pedido do devedor,
 * pelo próprio devedor, pelo credor ou por via jurídica. Enquanto aberta e
 * {@code blocksCollection}, os títulos ficam bloqueados no módulo debt e o caso fica em
 * {@code DISPUTED} (sem contato de cobrança). Descrição e parecer são texto livre e ficam
 * criptografados; evidências ({@link DisputeEvidence}) vão para o armazenamento de evidências.
 */
@Entity
@Table(name = "dispute", schema = "collection")
public class Dispute extends TenantScopedEntity {

    public static final int DESCRIPTION_MAX = 4000;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", nullable = false, updatable = false)
    private UUID portfolioId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30, updatable = false)
    private DisputeType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 20, updatable = false)
    private DisputeOrigin origin;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DisputeStatus status;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "description_encrypted", nullable = false, length = 24000, updatable = false)
    private String description;

    @Column(name = "blocks_collection", nullable = false, updatable = false)
    private boolean blocksCollection;

    @Column(name = "disputed_amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money disputedAmount;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "response_due_date", nullable = false)
    private LocalDate responseDueDate;

    @Column(name = "overdue", nullable = false)
    private boolean overdue;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 20)
    private DisputeOutcome outcome;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "resolution_note_encrypted", length = 24000)
    private String resolutionNote;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    /** Analista responsável (nulo = sem responsável). */
    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    /** Prazo interno (SLA da equipe), nunca depois do prazo de resposta ao devedor. */
    @Column(name = "internal_due_date", nullable = false)
    private LocalDate internalDueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "deadline_source", nullable = false, length = 20, updatable = false)
    private DeadlineSource deadlineSource;

    /** Desde quando aguarda o retorno do credor (nulo = não aguarda). */
    @Column(name = "awaiting_creditor_since")
    private Instant awaitingCreditorSince;

    @Enumerated(EnumType.STRING)
    @Column(name = "debtor_notice_status", length = 20)
    private DebtorNoticeStatus debtorNoticeStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_action", length = 30)
    private DisputeAdjustmentAction adjustmentAction;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_status", length = 20)
    private DisputeAdjustmentStatus adjustmentStatus;

    /** Saldo em aberto dos títulos contestados na resolução: referência para detectar o ajuste feito. */
    @Column(name = "adjustment_baseline", precision = 19, scale = 2)
    private Money adjustmentBaseline;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "adjustment_note_encrypted", length = 24000)
    private String adjustmentNote;

    @Column(name = "adjustment_closed_at")
    private Instant adjustmentClosedAt;

    @Column(name = "adjustment_closed_by")
    private UUID adjustmentClosedBy;

    /** Interação cuja tabulação abriu a contestação (ação automática), se houver. */
    @Column(name = "origin_interaction_id", updatable = false)
    private UUID originInteractionId;

    /**
     * Pedido externo que abriu a contestação (ex.: {@code portal_request:<id>} — contestação aberta pelo devedor no
     * portal). Chave de idempotência com o caso.
     */
    @Column(name = "origin_reference", length = 80, updatable = false)
    private String originReference;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "dispute_title", schema = "collection", joinColumns = @JoinColumn(name = "dispute_id"))
    @Column(name = "title_id", nullable = false)
    private Set<UUID> titleIds = new HashSet<>();

    protected Dispute() {
    }

    /**
     * @param internalDueDate prazo interno (SLA), limitado ao prazo de resposta
     * @param deadlineSource  origem do prazo na hierarquia
     */
    public Dispute(CollectionCase c, Collection<UUID> titleIds, DisputeType type, DisputeOrigin origin,
                   String description, boolean blocksCollection, Money disputedAmount, LocalDate responseDueDate,
                   LocalDate internalDueDate, DeadlineSource deadlineSource, UUID originInteractionId,
                   Instant now) {
        Guard.requireState(c.getStatus().isActive(), "case.closed", "Caso encerrado");
        Guard.require(titleIds != null && !titleIds.isEmpty(), "dispute.titles_required", "Informe os títulos contestados");
        Guard.require(type != null, "dispute.type_required", "Tipo da contestação obrigatório");
        Guard.require(origin != null, "dispute.origin_required", "Origem da contestação obrigatória");
        Guard.require(description != null && !description.isBlank() && description.trim().length() <= DESCRIPTION_MAX,
                "dispute.description_required", "Descrição obrigatória (até %d caracteres)".formatted(DESCRIPTION_MAX));
        Guard.require(responseDueDate != null, "dispute.due_required", "Prazo de resposta obrigatório");
        this.caseId = c.getId();
        this.debtorId = c.getDebtorId();
        this.creditorId = c.getCreditorId();
        this.portfolioId = c.getPortfolioId();
        this.titleIds.addAll(titleIds);
        this.type = type;
        this.origin = origin;
        this.description = description.trim();
        this.blocksCollection = blocksCollection;
        this.disputedAmount = disputedAmount == null ? Money.ZERO : disputedAmount;
        this.status = DisputeStatus.OPEN;
        this.openedAt = now;
        this.responseDueDate = responseDueDate;
        this.internalDueDate = internalDueDate == null || internalDueDate.isAfter(responseDueDate) ? responseDueDate
                : internalDueDate;
        this.deadlineSource = deadlineSource == null ? DeadlineSource.TENANT_DEFAULT : deadlineSource;
        this.originInteractionId = originInteractionId;
    }

    /** Registra a origem externa (só na abertura, antes de gravar). */
    public void originatedFrom(String reference) {
        Guard.requireState(this.originReference == null, "dispute.origin_reference_set", "Origem já registrada");
        Guard.require(reference == null || reference.matches("[a-z_]{2,40}:[0-9a-f\\-]{36}"),
                "dispute.origin_reference_invalid", "Referência de origem inválida");
        this.originReference = reference;
    }

    public String getOriginReference() {
        return originReference;
    }

    // ------------------------------------------------------------------ responsável

    /**
     * Define o responsável (nulo = sem responsável).
     *
     * @return o responsável anterior
     */
    public UUID assign(UUID assignee, Instant now) {
        Guard.requireState(status.isOpen(), "dispute.not_open", "Contestação já encerrada");
        Guard.requireState(!java.util.Objects.equals(assignee, assigneeId), "dispute.same_assignee",
                assignee == null ? "A contestação já está sem responsável" : "A contestação já é deste responsável");
        UUID previous = assigneeId;
        this.assigneeId = assignee;
        this.assignedAt = assignee == null ? null : now;
        return previous;
    }

    public boolean isAssignedTo(UUID userId) {
        return assigneeId != null && assigneeId.equals(userId);
    }

    // ------------------------------------------------------------------ credor

    /** Passou a aguardar o retorno do credor (pedido enviado); encaminha para análise se ainda aberta. */
    public void awaitCreditor(Instant now) {
        Guard.requireState(status.isOpen(), "dispute.not_open", "Contestação já encerrada");
        if (status == DisputeStatus.OPEN) {
            status = DisputeStatus.UNDER_REVIEW;
        }
        if (awaitingCreditorSince == null) {
            awaitingCreditorSince = now;
        }
    }

    /** Deixou de aguardar o credor (resposta recebida ou pedidos cancelados). */
    public void stopAwaitingCreditor() {
        awaitingCreditorSince = null;
    }

    // ------------------------------------------------------------------ devedor

    public void markDebtorNotified() {
        Guard.requireState(!status.isOpen(), "dispute.notice_before_resolution",
                "A resposta ao devedor é enviada depois da resolução");
        this.debtorNoticeStatus = DebtorNoticeStatus.SENT;
    }

    // ------------------------------------------------------------------ ajuste sugerido

    /** Sugere a ação no título a partir da resolução (sem efeito financeiro). */
    private void suggestAdjustment(DisputeAdjustmentAction action, Money baseline) {
        if (action == null) {
            return;
        }
        this.adjustmentAction = action;
        this.adjustmentStatus = DisputeAdjustmentStatus.PENDING;
        this.adjustmentBaseline = baseline == null ? Money.ZERO : baseline;
    }

    /**
     * Encerra a sugestão pendente: feita (detectada no título) ou dispensada com motivo.
     *
     * @return true se estava pendente
     */
    public boolean closeAdjustment(DisputeAdjustmentStatus outcome, String note, UUID by, Instant now) {
        Guard.require(outcome == DisputeAdjustmentStatus.DONE || outcome == DisputeAdjustmentStatus.DISMISSED,
                "dispute.adjustment_outcome_invalid", "Resultado do ajuste inválido");
        if (adjustmentStatus != DisputeAdjustmentStatus.PENDING) {
            return false;
        }
        if (outcome == DisputeAdjustmentStatus.DISMISSED) {
            Guard.require(note != null && !note.isBlank() && note.trim().length() <= DESCRIPTION_MAX,
                    "dispute.adjustment_reason_required", "Informe o motivo para dispensar o ajuste");
        }
        this.adjustmentStatus = outcome;
        this.adjustmentNote = note == null || note.isBlank() ? null : note.trim();
        this.adjustmentClosedAt = now;
        this.adjustmentClosedBy = by;
        return true;
    }

    public void startReview() {
        Guard.requireState(status == DisputeStatus.OPEN, "dispute.not_open", "Contestação não está aberta");
        this.status = DisputeStatus.UNDER_REVIEW;
    }

    public void resolve(DisputeOutcome outcome, String note, UUID by, Instant now) {
        resolve(outcome, note, by, now, null, null);
    }

    /**
     * @param adjustment ação sugerida no título (nula = nenhuma); só com resultado procedente ou parcial
     * @param baseline   saldo em aberto atual dos títulos contestados
     */
    public void resolve(DisputeOutcome outcome, String note, UUID by, Instant now, DisputeAdjustmentAction adjustment,
                        Money baseline) {
        Guard.requireState(status.isOpen(), "dispute.not_open", "Contestação já encerrada");
        Guard.require(adjustment == null || outcome != DisputeOutcome.REJECTED, "dispute.adjustment_not_applicable",
                "Contestação improcedente não sugere ajuste no título");
        Guard.require(outcome != null, "dispute.outcome_required", "Resultado obrigatório");
        Guard.require(note != null && !note.isBlank() && note.trim().length() <= DESCRIPTION_MAX,
                "dispute.resolution_required", "Parecer obrigatório (até %d caracteres)".formatted(DESCRIPTION_MAX));
        this.status = DisputeStatus.RESOLVED;
        this.outcome = outcome;
        this.resolutionNote = note.trim();
        this.resolvedAt = now;
        this.resolvedBy = by;
        this.awaitingCreditorSince = null;
        this.debtorNoticeStatus = origin.expectsDebtorResponse() ? DebtorNoticeStatus.PENDING
                : DebtorNoticeStatus.NOT_REQUIRED;
        suggestAdjustment(adjustment, baseline);
    }

    public void withdraw(String note, UUID by, Instant now) {
        Guard.requireState(status.isOpen(), "dispute.not_open", "Contestação já encerrada");
        Guard.require(note != null && !note.isBlank() && note.trim().length() <= DESCRIPTION_MAX,
                "dispute.resolution_required", "Motivo da desistência obrigatório");
        this.status = DisputeStatus.WITHDRAWN;
        this.resolutionNote = note.trim();
        this.resolvedAt = now;
        this.resolvedBy = by;
        this.awaitingCreditorSince = null;
        this.debtorNoticeStatus = DebtorNoticeStatus.NOT_REQUIRED;
    }

    /** Exige a contestação aberta para receber evidências. */
    public void requireOpenForEvidence() {
        Guard.requireState(status.isOpen(), "dispute.not_open", "Contestação já encerrada");
    }

    /** @return true se passou a estar vencida agora */
    public boolean flagOverdue(LocalDate today) {
        if (!status.isOpen() || overdue || !today.isAfter(responseDueDate)) {
            return false;
        }
        overdue = true;
        return true;
    }

    public boolean isBlocking() {
        return blocksCollection && status.isOpen();
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public DisputeType getType() {
        return type;
    }

    public DisputeOrigin getOrigin() {
        return origin;
    }

    public DisputeStatus getStatus() {
        return status;
    }

    public String getDescription() {
        return description;
    }

    public boolean isBlocksCollection() {
        return blocksCollection;
    }

    public Money getDisputedAmount() {
        return disputedAmount;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public LocalDate getResponseDueDate() {
        return responseDueDate;
    }

    public boolean isOverdue() {
        return overdue;
    }

    public DisputeOutcome getOutcome() {
        return outcome;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public UUID getResolvedBy() {
        return resolvedBy;
    }

    public UUID getAssigneeId() {
        return assigneeId;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public LocalDate getInternalDueDate() {
        return internalDueDate;
    }

    public DeadlineSource getDeadlineSource() {
        return deadlineSource;
    }

    public Instant getAwaitingCreditorSince() {
        return awaitingCreditorSince;
    }

    public DebtorNoticeStatus getDebtorNoticeStatus() {
        return debtorNoticeStatus;
    }

    public DisputeAdjustmentAction getAdjustmentAction() {
        return adjustmentAction;
    }

    public DisputeAdjustmentStatus getAdjustmentStatus() {
        return adjustmentStatus;
    }

    public Money getAdjustmentBaseline() {
        return adjustmentBaseline;
    }

    public String getAdjustmentNote() {
        return adjustmentNote;
    }

    public Instant getAdjustmentClosedAt() {
        return adjustmentClosedAt;
    }

    public UUID getAdjustmentClosedBy() {
        return adjustmentClosedBy;
    }

    public Set<UUID> getTitleIds() {
        return Set.copyOf(titleIds);
    }

    public UUID getOriginInteractionId() {
        return originInteractionId;
    }
}
