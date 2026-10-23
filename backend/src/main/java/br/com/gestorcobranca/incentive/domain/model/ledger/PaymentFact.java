package br.com.gestorcobranca.incentive.domain.model.ledger;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Pagamento confirmado e alocado a parcelas de acordo (fato comissionável, §5.3). Fica pendente
 * de lançamento até a atribuição do acordo existir (eventos chegam em qualquer ordem).
 *
 * @see CommissionLedgerEntry
 */
@Entity
@Table(name = "payment_fact", schema = "incentive")
public class PaymentFact extends TenantScopedEntity {

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "agreement_id", nullable = false, updatable = false)
    private UUID agreementId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", updatable = false)
    private UUID portfolioId;

    @Column(name = "total_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money totalAmount;

    /** Parte do pagamento alocada a parcelas do acordo (base comissionável). */
    @Column(name = "commissionable_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money commissionableAmount;

    @Column(name = "paid_at", nullable = false, updatable = false)
    private Instant paidAt;

    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private Instant confirmedAt;

    @Column(name = "posted_at")
    private Instant postedAt;

    protected PaymentFact() {
    }

    public PaymentFact(UUID sourceEventId, UUID paymentId, UUID agreementId, UUID creditorId, UUID portfolioId,
                       Money totalAmount, Money commissionableAmount, Instant paidAt, Instant confirmedAt) {
        Guard.require(paymentId != null && agreementId != null && creditorId != null, "payment_fact.ids_required",
                "Pagamento, acordo e credor obrigatórios");
        Guard.require(totalAmount != null && totalAmount.isPositive(), "payment_fact.amount_invalid", "Valor do pagamento inválido");
        Guard.require(commissionableAmount != null && !commissionableAmount.isNegative()
                && !commissionableAmount.isGreaterThan(totalAmount), "payment_fact.commissionable_invalid",
                "Valor comissionável inválido");
        this.sourceEventId = sourceEventId;
        this.paymentId = paymentId;
        this.agreementId = agreementId;
        this.creditorId = creditorId;
        this.portfolioId = portfolioId;
        this.totalAmount = totalAmount;
        this.commissionableAmount = commissionableAmount;
        this.paidAt = paidAt;
        this.confirmedAt = confirmedAt;
    }

    public void markPosted(Instant now) {
        Guard.requireState(postedAt == null, "payment_fact.already_posted", "Pagamento já lançado");
        this.postedAt = now;
    }

    public boolean isPosted() {
        return postedAt != null;
    }

    public UUID getSourceEventId() {
        return sourceEventId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getAgreementId() {
        return agreementId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public Money getTotalAmount() {
        return totalAmount;
    }

    public Money getCommissionableAmount() {
        return commissionableAmount;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getPostedAt() {
        return postedAt;
    }
}
