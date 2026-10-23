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
import java.util.UUID;

/**
 * Estorno de um pagamento: evento compensatório, append-only (gatilho e privilégios no banco). O
 * pagamento passa a {@code REVERSED} e cada alocação publicada gera um {@code PaymentReversed}.
 * {@code providerReference} é o id da devolução Pix, que torna idempotentes as notificações repetidas.
 */
@Entity
@Table(name = "payment_reversal", schema = "payment")
public class PaymentReversal extends TenantScopedEntity {

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "amount", nullable = false, updatable = false)
    private Money amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30, updatable = false)
    private ReversalReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20, updatable = false)
    private ReversalSource source;

    @Column(name = "provider_reference", length = 64, updatable = false)
    private String providerReference;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "note_encrypted", length = 4000, updatable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", nullable = false, length = 20, updatable = false)
    private PaymentStatus previousStatus;

    @Column(name = "reversed_at", nullable = false, updatable = false)
    private Instant reversedAt;

    protected PaymentReversal() {
    }

    public PaymentReversal(UUID paymentId, Money amount, ReversalReason reason, ReversalSource source,
                           String providerReference, String note, PaymentStatus previousStatus, Instant reversedAt) {
        Guard.require(paymentId != null, "reversal.payment_required", "Pagamento obrigatório");
        Guard.require(amount != null && amount.isPositive(), "reversal.amount_invalid", "Valor do estorno inválido");
        Guard.require(reason != null, "reversal.reason_required", "Motivo do estorno obrigatório");
        Guard.require(source != null, "reversal.source_required", "Origem do estorno obrigatória");
        Guard.require(note == null || note.length() <= Payment.NOTE_MAX, "reversal.note_too_long",
                "Observação até 1000 caracteres");
        Guard.require(source != ReversalSource.MANUAL || (note != null && !note.isBlank()), "reversal.note_required",
                "Informe a justificativa do estorno");
        this.paymentId = paymentId;
        this.amount = amount;
        this.reason = reason;
        this.source = source;
        this.providerReference = providerReference;
        this.note = note == null || note.isBlank() ? null : note.trim();
        this.previousStatus = previousStatus;
        this.reversedAt = reversedAt;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public Money getAmount() {
        return amount;
    }

    public ReversalReason getReason() {
        return reason;
    }

    public ReversalSource getSource() {
        return source;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getNote() {
        return note;
    }

    public PaymentStatus getPreviousStatus() {
        return previousStatus;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }
}
