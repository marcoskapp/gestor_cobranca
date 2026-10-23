package br.com.gestorcobranca.negotiation.domain.model.agreement;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/** Parcela do cronograma do acordo (o número 1 é o pagamento à vista ou a entrada). */
@Entity
@Table(name = "agreement_installment", schema = "negotiation")
public class AgreementInstallment extends TenantScopedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agreement_id", nullable = false, updatable = false)
    private Agreement agreement;

    @Column(name = "number", nullable = false, updatable = false)
    private int number;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money amount;

    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 2)
    private Money paidAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InstallmentStatus status;

    @Column(name = "paid_at")
    private Instant paidAt;

    protected AgreementInstallment() {
    }

    AgreementInstallment(Agreement agreement, int number, LocalDate dueDate, Money amount) {
        Guard.require(number >= 1, "installment.number_invalid", "Número da parcela inválido");
        Guard.require(dueDate != null, "installment.due_date_required", "Vencimento obrigatório");
        Guard.require(amount != null && amount.isPositive(), "installment.amount_invalid", "Valor da parcela positivo");
        this.agreement = agreement;
        this.number = number;
        this.dueDate = dueDate;
        this.amount = amount;
        this.paidAmount = Money.ZERO;
        this.status = InstallmentStatus.OPEN;
    }

    /** Situação calculada pelas regras de {@link InstallmentStatus}. */
    static InstallmentStatus statusFor(Money amount, Money paid, LocalDate dueDate, LocalDate today, boolean active) {
        if (!paid.isLessThan(amount)) {
            return InstallmentStatus.PAID;
        }
        if (!active) {
            return InstallmentStatus.CANCELLED;
        }
        if (dueDate.isBefore(today)) {
            return InstallmentStatus.OVERDUE;
        }
        return paid.isPositive() ? InstallmentStatus.PARTIALLY_PAID : InstallmentStatus.OPEN;
    }

    Money remaining() {
        return amount.minus(paidAmount).max(Money.ZERO);
    }

    /** Recebe até o saldo da parcela; devolve o valor efetivamente aplicado. */
    Money receive(Money value, Instant at) {
        Money applied = value.min(remaining());
        if (applied.isPositive()) {
            paidAmount = paidAmount.plus(applied);
            if (!paidAmount.isLessThan(amount)) {
                paidAt = at;
            }
        }
        return applied;
    }

    /** Estorno de valor recebido (nunca abaixo de zero). */
    void revert(Money value) {
        Guard.requireState(!value.isGreaterThan(paidAmount), "installment.reversal_exceeds_paid",
                "Estorno maior que o valor pago da parcela " + number);
        paidAmount = paidAmount.minus(value);
        if (paidAmount.isLessThan(amount)) {
            paidAt = null;
        }
    }

    /** Recalcula a situação; devolve a anterior quando mudou. */
    Optional<InstallmentStatus> refresh(LocalDate today, boolean agreementActive) {
        InstallmentStatus next = statusFor(amount, paidAmount, dueDate, today, agreementActive);
        if (next == status) {
            return Optional.empty();
        }
        InstallmentStatus previous = status;
        status = next;
        return Optional.of(previous);
    }

    /** Em atraso além da tolerância (conta para a regra de quebra). */
    boolean overdueBeyond(LocalDate today, int toleranceDays) {
        return status != InstallmentStatus.PAID && status != InstallmentStatus.CANCELLED
                && dueDate.plusDays(toleranceDays).isBefore(today);
    }

    public Agreement getAgreement() {
        return agreement;
    }

    public int getNumber() {
        return number;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public Money getAmount() {
        return amount;
    }

    public Money getPaidAmount() {
        return paidAmount;
    }

    public InstallmentStatus getStatus() {
        return status;
    }

    public Instant getPaidAt() {
        return paidAt;
    }
}
