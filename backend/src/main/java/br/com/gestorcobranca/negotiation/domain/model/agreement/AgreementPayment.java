package br.com.gestorcobranca.negotiation.domain.model.agreement;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Pagamento confirmado ({@code PaymentConfirmed}) aplicado a um acordo: quanto foi aplicado a cada
 * parcela, o excedente não aplicado e o plano de lançamentos no razão do título (encargo a
 * reconhecer e pagamento por título e componente). Os lançamentos são executados por um job
 * ({@code negotiation.ledger_posting}) em transação própria, de modo que uma recusa do razão não
 * desfaça a baixa da parcela e fique visível para tratamento ({@link LedgerStatus}).
 * Um registro por (acordo, pagamento): reentregas não duplicam a baixa.
 */
@Entity
@Table(name = "agreement_payment", schema = "negotiation")
public class AgreementPayment extends TenantScopedEntity {

    public enum Status { APPLIED, UNAPPLIED, REVERSED }

    public enum LedgerStatus {
        /** Aguardando o job de lançamento. */
        PENDING,
        /** Lançado no razão. */
        POSTED,
        /** Estorno lançado no razão. */
        REVERSED,
        /** Nada a lançar (não aplicado, ou estornado antes do lançamento). */
        SKIPPED
    }

    @Column(name = "agreement_id", nullable = false, updatable = false)
    private UUID agreementId;
    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;
    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money amount;
    @Column(name = "applied_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money appliedAmount;
    @Column(name = "unapplied_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money unappliedAmount;
    @Column(name = "paid_at", nullable = false, updatable = false)
    private Instant paidAt;
    @Column(name = "source", updatable = false, length = 20)
    private String source;
    @Column(name = "method", updatable = false, length = 20)
    private String method;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "installment_allocations", nullable = false, updatable = false)
    private String installmentAllocations;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ledger_postings", nullable = false, updatable = false)
    private String ledgerPostings;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;
    @Enumerated(EnumType.STRING)
    @Column(name = "ledger_status", nullable = false, length = 20)
    private LedgerStatus ledgerStatus;
    @Column(name = "ledger_posted_at")
    private Instant ledgerPostedAt;
    @Column(name = "reversal_id")
    private UUID reversalId;
    @Column(name = "reversed_at")
    private Instant reversedAt;
    @Column(name = "reversal_reason", length = 500)
    private String reversalReason;

    protected AgreementPayment() {
    }

    public AgreementPayment(UUID agreementId, UUID paymentId, Money amount, Money appliedAmount, Money unappliedAmount,
                            Instant paidAt, String source, String method, String installmentAllocationsJson,
                            String ledgerPostingsJson) {
        Guard.require(agreementId != null && paymentId != null && paidAt != null, "agreement_payment.invalid",
                "Acordo, pagamento e data obrigatórios");
        this.agreementId = agreementId;
        this.paymentId = paymentId;
        this.amount = amount;
        this.appliedAmount = appliedAmount;
        this.unappliedAmount = unappliedAmount;
        this.paidAt = paidAt;
        this.source = source;
        this.method = method;
        this.installmentAllocations = installmentAllocationsJson;
        this.ledgerPostings = ledgerPostingsJson;
        this.status = appliedAmount.isPositive() ? Status.APPLIED : Status.UNAPPLIED;
        this.ledgerStatus = appliedAmount.isPositive() ? LedgerStatus.PENDING : LedgerStatus.SKIPPED;
    }

    public void ledgerPosted(Instant at) {
        Guard.requireState(ledgerStatus == LedgerStatus.PENDING, "agreement_payment.ledger_state",
                "Lançamento não está pendente");
        ledgerStatus = LedgerStatus.POSTED;
        ledgerPostedAt = at;
    }

    /** Estorno; devolve a situação do razão antes do estorno (POSTED exige lançar o estorno no razão). */
    public LedgerStatus reverse(UUID reversalId, String reason, Instant at) {
        Guard.requireState(status == Status.APPLIED, "agreement_payment.not_applied", "Pagamento não aplicado ao acordo");
        LedgerStatus before = ledgerStatus;
        status = Status.REVERSED;
        this.reversalId = reversalId;
        this.reversedAt = at;
        this.reversalReason = reason == null ? null : reason.length() <= 500 ? reason : reason.substring(0, 500);
        if (before == LedgerStatus.PENDING) {
            // nunca chegou ao razão: o job de lançamento verá REVERSED e não lançará
            ledgerStatus = LedgerStatus.SKIPPED;
        }
        return before;
    }

    public void ledgerReversed() {
        ledgerStatus = LedgerStatus.REVERSED;
    }

    public UUID getAgreementId() { return agreementId; }
    public UUID getPaymentId() { return paymentId; }
    public Money getAmount() { return amount; }
    public Money getAppliedAmount() { return appliedAmount; }
    public Money getUnappliedAmount() { return unappliedAmount; }
    public Instant getPaidAt() { return paidAt; }
    public String getSource() { return source; }
    public String getMethod() { return method; }
    public String getInstallmentAllocations() { return installmentAllocations; }
    public String getLedgerPostings() { return ledgerPostings; }
    public Status getStatus() { return status; }
    public LedgerStatus getLedgerStatus() { return ledgerStatus; }
    public Instant getLedgerPostedAt() { return ledgerPostedAt; }
    public UUID getReversalId() { return reversalId; }
    public Instant getReversedAt() { return reversedAt; }
    public String getReversalReason() { return reversalReason; }
}
