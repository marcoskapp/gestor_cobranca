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

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "dispute_title", schema = "collection", joinColumns = @JoinColumn(name = "dispute_id"))
    @Column(name = "title_id", nullable = false, updatable = false)
    private Set<UUID> titleIds = new HashSet<>();

    protected Dispute() {
    }

    public Dispute(CollectionCase c, Collection<UUID> titleIds, DisputeType type, DisputeOrigin origin,
                   String description, boolean blocksCollection, Money disputedAmount, LocalDate responseDueDate,
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
    }

    public void startReview() {
        Guard.requireState(status == DisputeStatus.OPEN, "dispute.not_open", "Contestação não está aberta");
        this.status = DisputeStatus.UNDER_REVIEW;
    }

    public void resolve(DisputeOutcome outcome, String note, UUID by, Instant now) {
        Guard.requireState(status.isOpen(), "dispute.not_open", "Contestação já encerrada");
        Guard.require(outcome != null, "dispute.outcome_required", "Resultado obrigatório");
        Guard.require(note != null && !note.isBlank() && note.trim().length() <= DESCRIPTION_MAX,
                "dispute.resolution_required", "Parecer obrigatório (até %d caracteres)".formatted(DESCRIPTION_MAX));
        this.status = DisputeStatus.RESOLVED;
        this.outcome = outcome;
        this.resolutionNote = note.trim();
        this.resolvedAt = now;
        this.resolvedBy = by;
    }

    public void withdraw(String note, UUID by, Instant now) {
        Guard.requireState(status.isOpen(), "dispute.not_open", "Contestação já encerrada");
        Guard.require(note != null && !note.isBlank() && note.trim().length() <= DESCRIPTION_MAX,
                "dispute.resolution_required", "Motivo da desistência obrigatório");
        this.status = DisputeStatus.WITHDRAWN;
        this.resolutionNote = note.trim();
        this.resolvedAt = now;
        this.resolvedBy = by;
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

    public Set<UUID> getTitleIds() {
        return Set.copyOf(titleIds);
    }
}
