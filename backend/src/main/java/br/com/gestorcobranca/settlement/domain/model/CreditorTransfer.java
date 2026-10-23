package br.com.gestorcobranca.settlement.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Repasse do valor líquido de um demonstrativo aprovado ao credor ({@code AGENCY_TO_CREDITOR}) ou, com
 * líquido negativo, recebimento do credor ({@code CREDITOR_TO_AGENCY}), com comprovante obrigatório
 * (evidência no {@code FileStorage} com SHA-256). Colunas financeiras imutáveis; registro errado é
 * cancelado com motivo, nunca apagado.
 */
@Entity
@Table(name = "creditor_transfer", schema = "settlement")
public class CreditorTransfer extends TenantScopedEntity {

    public enum Direction { AGENCY_TO_CREDITOR, CREDITOR_TO_AGENCY }

    public enum Status { REGISTERED, CANCELLED }

    /** Comprovante armazenado. */
    public record Evidence(String key, String name, String contentType, long size, String sha256) {
    }

    @Column(name = "statement_id", nullable = false, updatable = false)
    private UUID statementId;
    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false, length = 20)
    private Direction direction;
    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money amount;
    @Column(name = "transferred_on", nullable = false, updatable = false)
    private LocalDate transferredOn;
    @Column(name = "bank_reference", nullable = false, updatable = false, length = 120)
    private String bankReference;
    @Column(name = "note", updatable = false, length = 1000)
    private String note;
    @Column(name = "evidence_key", nullable = false, updatable = false, length = 512)
    private String evidenceKey;
    @Column(name = "evidence_name", nullable = false, updatable = false, length = 200)
    private String evidenceName;
    @Column(name = "evidence_type", nullable = false, updatable = false, length = 100)
    private String evidenceType;
    @Column(name = "evidence_size", nullable = false, updatable = false)
    private long evidenceSize;
    @Column(name = "evidence_sha256", nullable = false, updatable = false, length = 64)
    private String evidenceSha256;
    @Column(name = "registered_by", nullable = false, updatable = false)
    private UUID registeredBy;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;
    @Column(name = "cancelled_by")
    private UUID cancelledBy;
    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    protected CreditorTransfer() {
    }

    public CreditorTransfer(SettlementStatement statement, Money amount, LocalDate transferredOn, LocalDate today,
                            String bankReference, String note, Evidence evidence, UUID registeredBy) {
        Guard.require(transferredOn != null && !transferredOn.isAfter(today), "transfer.date_invalid",
                "Data do repasse obrigatória e não futura");
        Guard.require(!transferredOn.isBefore(statement.getPeriodEnd()), "transfer.date_before_period",
                "Repasse anterior ao fim do período do demonstrativo");
        Guard.require(bankReference != null && !bankReference.isBlank() && bankReference.length() <= 120,
                "transfer.reference_required", "Referência bancária do repasse obrigatória (até 120 caracteres)");
        Guard.require(note == null || note.length() <= 1000, "transfer.note_too_long", "Observação até 1000 caracteres");
        Guard.require(evidence != null, "transfer.evidence_required", "Comprovante do repasse obrigatório");
        Guard.require(!statement.getNetTransferAmount().isZero(), "transfer.nothing_to_transfer",
                "Demonstrativo sem valor a repassar");
        this.statementId = statement.getId();
        this.creditorId = statement.getCreditorId();
        this.direction = statement.getNetTransferAmount().isPositive() ? Direction.AGENCY_TO_CREDITOR
                : Direction.CREDITOR_TO_AGENCY;
        this.amount = amount;
        this.transferredOn = transferredOn;
        this.bankReference = bankReference.trim();
        this.note = note == null || note.isBlank() ? null : note.trim();
        this.evidenceKey = evidence.key();
        this.evidenceName = evidence.name();
        this.evidenceType = evidence.contentType();
        this.evidenceSize = evidence.size();
        this.evidenceSha256 = evidence.sha256();
        this.registeredBy = registeredBy;
        this.status = Status.REGISTERED;
        statement.transferRegistered(amount);
    }

    public void cancel(SettlementStatement statement, UUID by, String reason, Instant at) {
        Guard.requireState(status == Status.REGISTERED, "transfer.already_cancelled", "Repasse já cancelado");
        Guard.require(reason != null && !reason.isBlank() && reason.length() <= 500, "transfer.cancel_reason_required",
                "Informe o motivo do cancelamento (até 500 caracteres)");
        this.status = Status.CANCELLED;
        this.cancelledAt = at;
        this.cancelledBy = by;
        this.cancelReason = reason.trim();
        statement.transferCancelled(amount);
    }

    public UUID getStatementId() { return statementId; }
    public UUID getCreditorId() { return creditorId; }
    public Direction getDirection() { return direction; }
    public Money getAmount() { return amount; }
    public LocalDate getTransferredOn() { return transferredOn; }
    public String getBankReference() { return bankReference; }
    public String getNote() { return note; }
    public String getEvidenceKey() { return evidenceKey; }
    public String getEvidenceName() { return evidenceName; }
    public String getEvidenceType() { return evidenceType; }
    public long getEvidenceSize() { return evidenceSize; }
    public String getEvidenceSha256() { return evidenceSha256; }
    public UUID getRegisteredBy() { return registeredBy; }
    public Status getStatus() { return status; }
    public Instant getCancelledAt() { return cancelledAt; }
    public UUID getCancelledBy() { return cancelledBy; }
    public String getCancelReason() { return cancelReason; }
}
