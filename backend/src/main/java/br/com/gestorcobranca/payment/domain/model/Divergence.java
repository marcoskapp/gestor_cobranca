package br.com.gestorcobranca.payment.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Divergência de conciliação na fila de análise manual (F15): valor diferente, pagamento em
 * duplicidade, crédito sem correspondência, pagamento ainda não confirmado ou devolução parcial.
 * Resolução auditada: vincular a um pagamento, aceitar como está ou descartar, sempre com justificativa.
 * Sem credor ({@code creditorId} nulo) só é visível a quem tem acesso a todos os credores.
 */
@Entity
@Table(name = "divergence", schema = "payment")
public class Divergence extends TenantScopedEntity {

    public static final int NOTE_MAX = 1000;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30, updatable = false)
    private DivergenceType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20, updatable = false)
    private DivergenceSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DivergenceStatus status;

    @Column(name = "creditor_id", updatable = false)
    private UUID creditorId;

    @Column(name = "payment_id", updatable = false)
    private UUID paymentId;

    @Column(name = "instruction_id", updatable = false)
    private UUID instructionId;

    @Column(name = "statement_line_id", updatable = false)
    private UUID statementLineId;

    @Column(name = "provider_reference", length = 64, updatable = false)
    private String providerReference;

    @Column(name = "amount", nullable = false, updatable = false)
    private Money amount;

    @Column(name = "expected_amount", updatable = false)
    private Money expectedAmount;

    @Column(name = "occurred_on", nullable = false, updatable = false)
    private LocalDate occurredOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", length = 30)
    private DivergenceResolution resolution;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "resolution_note_encrypted", length = 4000)
    private String resolutionNote;

    @Column(name = "resolved_payment_id")
    private UUID resolvedPaymentId;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected Divergence() {
    }

    public Divergence(DivergenceType type, DivergenceSource source, UUID creditorId, UUID paymentId, UUID instructionId,
                      UUID statementLineId, String providerReference, Money amount, Money expectedAmount,
                      LocalDate occurredOn) {
        Guard.require(type != null && source != null, "divergence.type_required", "Tipo e origem obrigatórios");
        Guard.require(amount != null && occurredOn != null, "divergence.amount_required", "Valor e data obrigatórios");
        this.type = type;
        this.source = source;
        this.status = DivergenceStatus.OPEN;
        this.creditorId = creditorId;
        this.paymentId = paymentId;
        this.instructionId = instructionId;
        this.statementLineId = statementLineId;
        this.providerReference = providerReference;
        this.amount = amount;
        this.expectedAmount = expectedAmount;
        this.occurredOn = occurredOn;
    }

    public void resolve(DivergenceResolution resolution, UUID resolvedPaymentId, String note, UUID by, Instant now) {
        Guard.requireState(status == DivergenceStatus.OPEN, "divergence.not_open", "Divergência já resolvida");
        Guard.require(resolution != null, "divergence.resolution_required", "Resolução obrigatória");
        Guard.require(note != null && !note.isBlank() && note.length() <= NOTE_MAX, "divergence.note_required",
                "Justificativa obrigatória (até 1000 caracteres)");
        Guard.require(resolution != DivergenceResolution.MATCHED_PAYMENT || resolvedPaymentId != null,
                "divergence.payment_required", "Informe o pagamento correspondente");
        this.resolution = resolution;
        this.status = resolution == DivergenceResolution.DISMISSED ? DivergenceStatus.DISMISSED : DivergenceStatus.RESOLVED;
        this.resolvedPaymentId = resolvedPaymentId;
        this.resolutionNote = note.trim();
        this.resolvedBy = by;
        this.resolvedAt = now;
    }

    public DivergenceType getType() {
        return type;
    }

    public DivergenceSource getSource() {
        return source;
    }

    public DivergenceStatus getStatus() {
        return status;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getInstructionId() {
        return instructionId;
    }

    public UUID getStatementLineId() {
        return statementLineId;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public Money getAmount() {
        return amount;
    }

    public Money getExpectedAmount() {
        return expectedAmount;
    }

    public LocalDate getOccurredOn() {
        return occurredOn;
    }

    public DivergenceResolution getResolution() {
        return resolution;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public UUID getResolvedPaymentId() {
        return resolvedPaymentId;
    }

    public UUID getResolvedBy() {
        return resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
