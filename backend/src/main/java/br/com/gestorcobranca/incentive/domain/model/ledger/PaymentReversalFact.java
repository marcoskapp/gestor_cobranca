package br.com.gestorcobranca.incentive.domain.model.ledger;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Estorno de pagamento recebido; gera lançamentos compensatórios quando o pagamento já foi lançado. */
@Entity
@Table(name = "payment_reversal_fact", schema = "incentive")
public class PaymentReversalFact extends TenantScopedEntity {

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    @Column(name = "reversal_id", nullable = false, updatable = false)
    private UUID reversalId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "agreement_id", updatable = false)
    private UUID agreementId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money amount;

    @Column(name = "reason", updatable = false, length = 200)
    private String reason;

    @Column(name = "reversed_at", nullable = false, updatable = false)
    private Instant reversedAt;

    @Column(name = "posted_at")
    private Instant postedAt;

    protected PaymentReversalFact() {
    }

    public PaymentReversalFact(UUID sourceEventId, UUID reversalId, UUID paymentId, UUID agreementId, Money amount,
                               String reason, Instant reversedAt) {
        Guard.require(reversalId != null && paymentId != null, "payment_reversal.ids_required", "Estorno e pagamento obrigatórios");
        Guard.require(amount != null && amount.isPositive(), "payment_reversal.amount_invalid", "Valor do estorno inválido");
        this.sourceEventId = sourceEventId;
        this.reversalId = reversalId;
        this.paymentId = paymentId;
        this.agreementId = agreementId;
        this.amount = amount;
        this.reason = reason == null || reason.length() <= 200 ? reason : reason.substring(0, 200);
        this.reversedAt = reversedAt;
    }

    public void markPosted(Instant now) {
        Guard.requireState(postedAt == null, "payment_reversal.already_posted", "Estorno já lançado");
        this.postedAt = now;
    }

    public UUID getSourceEventId() {
        return sourceEventId;
    }

    public UUID getReversalId() {
        return reversalId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getAgreementId() {
        return agreementId;
    }

    public Money getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }

    public Instant getPostedAt() {
        return postedAt;
    }
}
