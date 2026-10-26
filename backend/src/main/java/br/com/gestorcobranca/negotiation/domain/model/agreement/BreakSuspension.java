package br.com.gestorcobranca.negotiation.domain.model.agreement;

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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Suspensão da quebra do acordo enquanto há pagamento externo informado aguardando a revisão de outra
 * pessoa (decisão do responsável de 26/10/2026). Começa no job diário, quando a regra de quebra é
 * atingida com pagamento em revisão para o acordo; termina uma única vez: revisão concluída (aprovado
 * ou recusado — este com carência), prazo máximo vencido ou acordo encerrado. Datas no fuso de operação.
 * <p>
 * Todas as mudanças acontecem com o acordo bloqueado ({@code FOR UPDATE}); {@code @Version} protege
 * contra gravações concorrentes fora desse caminho.
 */
@Entity
@Table(name = "agreement_break_suspension", schema = "negotiation")
public class BreakSuspension extends TenantScopedEntity {

    @Column(name = "agreement_id", nullable = false, updatable = false)
    private UUID agreementId;
    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;
    @Column(name = "trigger_installment_id", nullable = false, updatable = false)
    private UUID triggerInstallmentId;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BreakSuspensionStatus status;
    @Column(name = "started_on", nullable = false, updatable = false)
    private LocalDate startedOn;
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;
    @Column(name = "suspended_until", nullable = false, updatable = false)
    private LocalDate suspendedUntil;
    @Column(name = "pending_payments", nullable = false, updatable = false)
    private int pendingPayments;
    @Column(name = "pending_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money pendingAmount;
    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 20)
    private BreakSuspensionEndReason endReason;
    @Column(name = "ended_at")
    private Instant endedAt;
    @Column(name = "grace_until")
    private LocalDate graceUntil;
    @Column(name = "reviewed_payment_id")
    private UUID reviewedPaymentId;

    protected BreakSuspension() {
    }

    /**
     * @param maxDays prazo máximo: a quebra fica suspensa até {@code today + maxDays} (inclusive)
     */
    public BreakSuspension(Agreement agreement, AgreementInstallment trigger, int pendingPayments, Money pendingAmount,
                           LocalDate today, int maxDays, Instant at) {
        Guard.requireState(agreement.getStatus().isActive(), "agreement.not_active",
                "Só a quebra de um acordo em vigor pode ser suspensa");
        Guard.require(trigger != null && trigger.getAgreement() == agreement, "agreement.break_suspension_invalid",
                "Parcela que dispara a quebra obrigatória");
        Guard.require(pendingPayments > 0 && pendingAmount != null && pendingAmount.isPositive(),
                "agreement.break_suspension_invalid", "Suspensão exige pagamento em revisão");
        Guard.require(maxDays >= 1, "agreement.break_suspension_invalid", "Prazo máximo de suspensão inválido");
        this.agreementId = agreement.getId();
        this.creditorId = agreement.getCreditorId();
        this.triggerInstallmentId = trigger.getId();
        this.status = BreakSuspensionStatus.ACTIVE;
        this.startedOn = today;
        this.startedAt = at;
        this.suspendedUntil = today.plusDays(maxDays);
        this.pendingPayments = pendingPayments;
        this.pendingAmount = pendingAmount;
    }

    /** Prazo máximo vencido no dia de referência (a quebra volta a valer a partir do dia seguinte ao prazo). */
    public boolean deadlinePassed(LocalDate today) {
        return today.isAfter(suspendedUntil);
    }

    /** Revisão concluída: aprovado (sem carência) ou recusado (carência até {@code graceUntil}, inclusive). */
    public void endByReview(boolean approved, UUID reviewedPaymentId, LocalDate today, int graceDays, Instant at) {
        // a carência conta do dia da recusa, nunca antes do início da suspensão
        LocalDate anchor = today.isBefore(startedOn) ? startedOn : today;
        end(approved ? BreakSuspensionEndReason.APPROVED : BreakSuspensionEndReason.REJECTED,
                approved ? null : anchor.plusDays(Math.max(0, graceDays)), reviewedPaymentId, at);
    }

    public void endByDeadline(Instant at) {
        end(BreakSuspensionEndReason.DEADLINE, null, null, at);
    }

    public void endByAgreementEnded(Instant at) {
        end(BreakSuspensionEndReason.AGREEMENT_ENDED, null, null, at);
    }

    private void end(BreakSuspensionEndReason reason, LocalDate grace, UUID paymentId, Instant at) {
        Guard.requireState(isActive(), "agreement.break_suspension_ended", "Suspensão da quebra já encerrada");
        this.status = BreakSuspensionStatus.ENDED;
        this.endReason = reason;
        this.endedAt = at;
        this.graceUntil = grace;
        this.reviewedPaymentId = paymentId;
    }

    /** Carência depois da recusa em curso no dia de referência (a quebra ainda não volta a valer). */
    public boolean inGrace(LocalDate today) {
        return endReason == BreakSuspensionEndReason.REJECTED && graceUntil != null && !today.isAfter(graceUntil);
    }

    public boolean isActive() {
        return status == BreakSuspensionStatus.ACTIVE;
    }

    /** Dias corridos entre o início da suspensão e {@code endedOn}. */
    public long durationDays(LocalDate endedOn) {
        return Math.max(0, ChronoUnit.DAYS.between(startedOn, endedOn));
    }

    public UUID getAgreementId() { return agreementId; }
    public UUID getCreditorId() { return creditorId; }
    public UUID getTriggerInstallmentId() { return triggerInstallmentId; }
    public BreakSuspensionStatus getStatus() { return status; }
    public LocalDate getStartedOn() { return startedOn; }
    public Instant getStartedAt() { return startedAt; }
    public LocalDate getSuspendedUntil() { return suspendedUntil; }
    public int getPendingPayments() { return pendingPayments; }
    public Money getPendingAmount() { return pendingAmount; }
    public BreakSuspensionEndReason getEndReason() { return endReason; }
    public Instant getEndedAt() { return endedAt; }
    public LocalDate getGraceUntil() { return graceUntil; }
    public UUID getReviewedPaymentId() { return reviewedPaymentId; }
}
