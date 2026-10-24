package br.com.gestorcobranca.settlement.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Estorno recebido do payment ({@code PaymentReversed}) para uma alocação. Guardado mesmo que o
 * recebimento ainda não exista (os eventos chegam em qualquer ordem): quando ele for registrado, o
 * saldo ainda não compensado é aplicado. Estorno de alocação sem recuperação (ex.: realocação de valor
 * que o negotiation não aplicou) fica sem efeito.
 */
@Entity
@Table(name = "reversal_notice", schema = "settlement")
public class ReversalNotice extends TenantScopedEntity {

    @Column(name = "reversal_id", nullable = false, updatable = false)
    private UUID reversalId;
    @Column(name = "allocation_id", nullable = false, updatable = false)
    private UUID allocationId;
    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;
    @Column(name = "agreement_id", updatable = false)
    private UUID agreementId;
    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money amount;
    @Column(name = "applied_amount", nullable = false, precision = 19, scale = 2)
    private Money appliedAmount;
    @Column(name = "reason", updatable = false, length = 40)
    private String reason;
    @Column(name = "reversed_on", nullable = false, updatable = false)
    private LocalDate reversedOn;

    protected ReversalNotice() {
    }

    public ReversalNotice(UUID reversalId, UUID allocationId, UUID creditorId, UUID agreementId, Money amount,
                          String reason, LocalDate reversedOn) {
        Guard.require(reversalId != null && allocationId != null && creditorId != null && reversedOn != null,
                "reversal_notice.invalid", "Estorno sem identificação");
        Guard.require(amount != null && amount.isPositive(), "reversal_notice.amount_invalid", "Estorno deve ser positivo");
        this.reversalId = reversalId;
        this.allocationId = allocationId;
        this.creditorId = creditorId;
        this.agreementId = agreementId;
        this.amount = amount;
        this.appliedAmount = Money.ZERO;
        this.reason = reason == null ? null : reason.length() <= 40 ? reason : reason.substring(0, 40);
        this.reversedOn = reversedOn;
    }

    public Money pending() {
        return amount.minus(appliedAmount);
    }

    public void apply(Money value) {
        Guard.requireState(!value.isGreaterThan(pending()), "reversal_notice.overapplied", "Estorno já compensado");
        this.appliedAmount = appliedAmount.plus(value);
    }

    public UUID getReversalId() { return reversalId; }
    public UUID getAllocationId() { return allocationId; }
    public UUID getCreditorId() { return creditorId; }
    public UUID getAgreementId() { return agreementId; }
    public Money getAmount() { return amount; }
    public Money getAppliedAmount() { return appliedAmount; }
    public String getReason() { return reason; }
    public LocalDate getReversedOn() { return reversedOn; }
}
