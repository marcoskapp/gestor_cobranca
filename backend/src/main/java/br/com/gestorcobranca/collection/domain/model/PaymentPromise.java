package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Promessa de pagamento simples (sem acordo formal): valor e data prometidos pelo devedor num
 * contato. O cumprimento é apurado pelos pagamentos <strong>confirmados</strong> (RB08) nos
 * títulos do caso a partir do registro; a quebra, pela passagem do prazo de tolerância
 * ({@code breaksAfter}, fixado no registro para que mudanças de configuração não alterem
 * promessas já feitas).
 */
@Entity
@Table(name = "promise", schema = "collection")
public class PaymentPromise extends TenantScopedEntity {

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", nullable = false, updatable = false)
    private UUID portfolioId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money amount;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    /** Último dia em que o pagamento ainda cumpre a promessa (vencimento + tolerância). */
    @Column(name = "breaks_after", nullable = false, updatable = false)
    private LocalDate breaksAfter;

    @Column(name = "promised_at", nullable = false, updatable = false)
    private Instant promisedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PromiseStatus status;

    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 2)
    private Money paidAmount;

    @Column(name = "origin_interaction_id", updatable = false)
    private UUID originInteractionId;

    @Column(name = "operator_id", updatable = false)
    private UUID operatorId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_reason", length = 500)
    private String resolutionReason;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "promise_payment", schema = "collection", joinColumns = @JoinColumn(name = "promise_id"))
    private List<Payment> payments = new ArrayList<>();

    protected PaymentPromise() {
    }

    public PaymentPromise(CollectionCase c, Money amount, LocalDate dueDate, int toleranceDays, LocalDate today,
                          UUID originInteractionId, UUID operatorId, Instant now) {
        Guard.requireState(c.getStatus().isActive(), "case.closed", "Caso encerrado");
        Guard.require(amount != null && amount.isPositive(), "promise.amount_invalid", "Valor prometido deve ser positivo");
        Guard.require(!amount.isGreaterThan(c.getOpenBalance()), "promise.amount_above_balance",
                "Valor prometido maior que o saldo em aberto do caso");
        Guard.require(dueDate != null && !dueDate.isBefore(today), "promise.due_in_past",
                "Data prometida não pode estar no passado");
        Guard.require(toleranceDays >= 0, "promise.tolerance_invalid", "Tolerância não pode ser negativa");
        this.caseId = c.getId();
        this.debtorId = c.getDebtorId();
        this.creditorId = c.getCreditorId();
        this.portfolioId = c.getPortfolioId();
        this.amount = amount;
        this.dueDate = dueDate;
        this.breaksAfter = dueDate.plusDays(toleranceDays);
        this.promisedAt = now;
        this.status = PromiseStatus.PENDING;
        this.paidAmount = Money.ZERO;
        this.originInteractionId = originInteractionId;
        this.operatorId = operatorId;
    }

    /**
     * Pagamento confirmado alocado aos títulos do caso. Idempotente por pagamento.
     *
     * @return true se a promessa passou a cumprida
     */
    public boolean registerPayment(UUID paymentId, Money value, Instant paidAt, Instant now) {
        if (payments.stream().anyMatch(p -> p.paymentId.equals(paymentId)) || paidAt.isBefore(promisedAt)) {
            return false;
        }
        if (status != PromiseStatus.PENDING && status != PromiseStatus.KEPT) {
            return false;
        }
        payments.add(new Payment(paymentId, value, paidAt));
        paidAmount = paidAmount.plus(value);
        if (status == PromiseStatus.PENDING && paidAmount.isGreaterThanOrEqual(amount)) {
            status = PromiseStatus.KEPT;
            resolvedAt = now;
            resolutionReason = "Pagamentos confirmados alcançaram o valor prometido";
            return true;
        }
        return false;
    }

    /**
     * Estorno de um pagamento considerado. Uma promessa cumprida volta a pendente se o valor
     * pago ficar abaixo do prometido (o job de vencimento a quebra se o prazo já passou).
     *
     * @return true se a promessa deixou de estar cumprida
     */
    public boolean reversePayment(UUID paymentId) {
        Payment payment = payments.stream().filter(p -> p.paymentId.equals(paymentId)).findFirst().orElse(null);
        if (payment == null) {
            return false;
        }
        payments.remove(payment);
        paidAmount = paidAmount.minus(payment.amount).max(Money.ZERO);
        if (status == PromiseStatus.KEPT && paidAmount.isLessThan(amount)) {
            status = PromiseStatus.PENDING;
            resolvedAt = null;
            resolutionReason = "Pagamento estornado";
            return true;
        }
        return false;
    }

    /** @return true se a promessa foi quebrada agora (prazo de tolerância vencido na data) */
    public boolean breakIfOverdue(LocalDate today, Instant now) {
        if (status != PromiseStatus.PENDING || !today.isAfter(breaksAfter)) {
            return false;
        }
        status = PromiseStatus.BROKEN;
        resolvedAt = now;
        resolutionReason = paidAmount.isPositive() ? "Pagamento parcial até o prazo" : "Sem pagamento até o prazo";
        return true;
    }

    public void cancel(String reason, Instant now) {
        Guard.requireState(status == PromiseStatus.PENDING, "promise.not_pending", "Promessa não está pendente");
        Guard.require(reason != null && !reason.isBlank() && reason.trim().length() <= 500, "promise.reason_required",
                "Motivo do cancelamento obrigatório (até 500 caracteres)");
        status = PromiseStatus.CANCELLED;
        resolvedAt = now;
        resolutionReason = reason.trim();
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

    public Money getAmount() {
        return amount;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public LocalDate getBreaksAfter() {
        return breaksAfter;
    }

    public Instant getPromisedAt() {
        return promisedAt;
    }

    public PromiseStatus getStatus() {
        return status;
    }

    public Money getPaidAmount() {
        return paidAmount;
    }

    public UUID getOriginInteractionId() {
        return originInteractionId;
    }

    public UUID getOperatorId() {
        return operatorId;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getResolutionReason() {
        return resolutionReason;
    }

    public List<Payment> getPayments() {
        return List.copyOf(payments);
    }

    /** Pagamento confirmado considerado no cumprimento. */
    @Embeddable
    public static class Payment {

        @Column(name = "payment_id", nullable = false)
        private UUID paymentId;

        @Column(name = "amount", nullable = false, precision = 19, scale = 2)
        private Money amount;

        @Column(name = "paid_at", nullable = false)
        private Instant paidAt;

        protected Payment() {
        }

        Payment(UUID paymentId, Money amount, Instant paidAt) {
            this.paymentId = paymentId;
            this.amount = amount;
            this.paidAt = paidAt;
        }

        public UUID getPaymentId() {
            return paymentId;
        }

        public Money getAmount() {
            return amount;
        }

        public Instant getPaidAt() {
            return paidAt;
        }
    }
}
