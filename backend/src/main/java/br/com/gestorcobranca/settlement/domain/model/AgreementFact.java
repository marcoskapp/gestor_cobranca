package br.com.gestorcobranca.settlement.domain.model;

import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Acordo conhecido por evento ({@code AgreementAttributionRecorded}, {@code AgreementStatusChanged}):
 * base do valor negociado do período e da comissão prevista (informativa — acordo não pago não é
 * recuperação, RB08).
 */
@Entity
@Table(name = "agreement_fact", schema = "settlement")
public class AgreementFact extends TenantScopedEntity {

    private static final Set<String> OPEN = Set.of("FORMALIZED", "IN_PAYMENT");

    @Column(name = "agreement_id", nullable = false, updatable = false)
    private UUID agreementId;
    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;
    @Column(name = "portfolio_id")
    private UUID portfolioId;
    @Column(name = "status", nullable = false, length = 20)
    private String status;
    @Column(name = "agreement_amount", nullable = false, precision = 19, scale = 2)
    private Money agreementAmount;
    @Column(name = "days_past_due")
    private Integer daysPastDue;
    @Column(name = "installment_count")
    private Integer installmentCount;
    @Column(name = "renegotiation", nullable = false)
    private boolean renegotiation;
    @Column(name = "formalized_at")
    private Instant formalizedAt;
    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    protected AgreementFact() {
    }

    public AgreementFact(UUID agreementId, UUID creditorId, UUID portfolioId, String status, Money amount,
                         Instant statusChangedAt) {
        this.agreementId = agreementId;
        this.creditorId = creditorId;
        this.portfolioId = portfolioId;
        this.status = status;
        this.agreementAmount = amount == null ? Money.ZERO : amount;
        this.statusChangedAt = statusChangedAt;
    }

    /** Dados da formalização (atribuição). */
    public void formalized(UUID portfolio, Money amount, Integer daysPastDue, Integer installmentCount,
                           boolean renegotiation, Instant formalizedAt) {
        if (portfolio != null) {
            this.portfolioId = portfolio;
        }
        if (amount != null && amount.isPositive()) {
            this.agreementAmount = amount;
        }
        this.daysPastDue = daysPastDue;
        if (installmentCount != null) {
            this.installmentCount = installmentCount;
        }
        this.renegotiation = renegotiation;
        this.formalizedAt = formalizedAt;
    }

    /** Situação mais recente (eventos fora de ordem não regridem). */
    public void statusChanged(String newStatus, Instant at, Money totalAmount, boolean isFormalization) {
        if (isFormalization && formalizedAt == null) {
            formalizedAt = at;
        }
        if (totalAmount != null && totalAmount.isPositive() && !agreementAmount.isPositive()) {
            agreementAmount = totalAmount;
        }
        if (at.isBefore(statusChangedAt)) {
            return;
        }
        this.status = newStatus;
        this.statusChangedAt = at;
    }

    public boolean isOpen() {
        return OPEN.contains(status);
    }

    public UUID getAgreementId() { return agreementId; }
    public UUID getCreditorId() { return creditorId; }
    public UUID getPortfolioId() { return portfolioId; }
    public String getStatus() { return status; }
    public Money getAgreementAmount() { return agreementAmount; }
    public Integer getDaysPastDue() { return daysPastDue; }
    public Integer getInstallmentCount() { return installmentCount; }
    public boolean isRenegotiation() { return renegotiation; }
    public Instant getFormalizedAt() { return formalizedAt; }
    public Instant getStatusChangedAt() { return statusChangedAt; }
}
