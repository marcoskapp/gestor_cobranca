package br.com.gestorcobranca.payment.domain.model;

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
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Parte de um pagamento destinada a UM destino: parcelas de um acordo ({@link TargetKind#AGREEMENT}),
 * títulos avulsos ({@link TargetKind#TITLES}) ou devolução ao pagador ({@link TargetKind#REFUND}).
 * <p>
 * Cada alocação a acordo ou títulos é publicada como um {@code PaymentConfirmed} cujo
 * {@code paymentId} é o id desta alocação (unidade de baixa): os consumidores deduplicam por esse id,
 * e um pagamento distribuído entre destinos — ou realocado — nunca é contado duas vezes. As linhas são
 * imutáveis; o estorno reverte a alocação inteira.
 * <p>
 * Acordo: o negotiation informa o resultado da baixa ({@link AllocationOutcome}); a parte não
 * aplicada (acordo encerrado ou excedente) pode ser liberada ({@link #release}) e realocada.
 * {@code compensatedAmount} soma o que já foi compensado aos consumidores por {@code PaymentReversed}.
 */
@Entity
@Table(name = "payment_allocation", schema = "payment")
public class PaymentAllocation extends TenantScopedEntity {

    public static final int MAX_LINES = 200;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20, updatable = false)
    private TargetKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 20, updatable = false)
    private AllocationOrigin origin;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AllocationStatus status;

    @Column(name = "agreement_id", updatable = false)
    private UUID agreementId;

    @Column(name = "portfolio_id", updatable = false)
    private UUID portfolioId;

    @Column(name = "case_id", updatable = false)
    private UUID caseId;

    @Column(name = "amount", nullable = false, updatable = false)
    private Money amount;

    @Column(name = "released_amount", nullable = false)
    private Money releasedAmount;

    @Column(name = "compensated_amount", nullable = false)
    private Money compensatedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private AllocationOutcome outcome;

    @Column(name = "applied_amount")
    private Money appliedAmount;

    @Column(name = "unapplied_amount")
    private Money unappliedAmount;

    @Column(name = "outcome_at")
    private Instant outcomeAt;

    @Column(name = "source_allocation_id", updatable = false)
    private UUID sourceAllocationId;

    @Column(name = "refund_reference", length = 100, updatable = false)
    private String refundReference;

    @Column(name = "reversal_event_id")
    private UUID reversalEventId;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "payment_allocation_line", schema = "payment", joinColumns = @JoinColumn(name = "allocation_id"))
    @OrderColumn(name = "line_order")
    private List<Line> lines = new ArrayList<>();

    protected PaymentAllocation() {
    }

    private PaymentAllocation(UUID paymentId, TargetKind kind, AllocationOrigin origin, UUID agreementId, UUID portfolioId,
                              UUID caseId, Money amount, List<Line> lines, UUID sourceAllocationId,
                              String refundReference) {
        Guard.require(paymentId != null, "allocation.payment_required", "Pagamento obrigatório");
        Guard.require(amount != null && amount.isPositive(), "allocation.amount_invalid", "Valor alocado deve ser positivo");
        Guard.require(lines.size() <= MAX_LINES, "allocation.too_many_lines", "Alocação com linhas demais");
        this.paymentId = paymentId;
        this.kind = kind;
        this.origin = origin == null ? AllocationOrigin.INITIAL : origin;
        this.status = AllocationStatus.ACTIVE;
        this.agreementId = agreementId;
        this.portfolioId = portfolioId;
        this.caseId = caseId;
        this.amount = amount;
        this.releasedAmount = Money.ZERO;
        this.compensatedAmount = Money.ZERO;
        this.lines = new ArrayList<>(lines);
        this.sourceAllocationId = sourceAllocationId;
        this.refundReference = refundReference;
        this.outcome = kind == TargetKind.AGREEMENT ? AllocationOutcome.PENDING : AllocationOutcome.NOT_APPLICABLE;
    }

    /** Alocação às parcelas de um acordo (a distribuição entre títulos e o razão são do negotiation). */
    public static PaymentAllocation toAgreement(UUID paymentId, AllocationOrigin origin, UUID agreementId, UUID portfolioId,
                                                UUID caseId, List<Line> lines, UUID sourceAllocationId) {
        Guard.require(agreementId != null, "allocation.agreement_required", "Acordo obrigatório");
        Guard.require(lines != null && !lines.isEmpty() && lines.stream().allMatch(l -> l.installmentId() != null
                && l.titleId() == null), "allocation.installments_required", "Informe as parcelas do acordo");
        return new PaymentAllocation(paymentId, TargetKind.AGREEMENT, origin, agreementId, portfolioId, caseId,
                sum(lines), lines, sourceAllocationId, null);
    }

    /** Alocação a títulos avulsos (lançados no razão do título pelo payment). */
    public static PaymentAllocation toTitles(UUID paymentId, AllocationOrigin origin, UUID portfolioId, List<Line> lines,
                                             UUID sourceAllocationId) {
        Guard.require(lines != null && !lines.isEmpty() && lines.stream().allMatch(l -> l.titleId() != null
                && l.installmentId() == null), "allocation.titles_required", "Informe os títulos");
        return new PaymentAllocation(paymentId, TargetKind.TITLES, origin, null, portfolioId, null, sum(lines), lines,
                sourceAllocationId, null);
    }

    /** Devolução ao pagador de um saldo não aplicado (registro; a transferência é feita fora do sistema). */
    public static PaymentAllocation refund(UUID paymentId, Money amount, String reference, UUID sourceAllocationId) {
        Guard.require(reference != null && !reference.isBlank() && reference.length() <= 100,
                "allocation.refund_reference_required", "Informe a referência da devolução (até 100 caracteres)");
        return new PaymentAllocation(paymentId, TargetKind.REFUND, AllocationOrigin.REALLOCATION, null, null, null, amount,
                List.of(), sourceAllocationId, reference.trim());
    }

    /**
     * Resultado da baixa informado pelo negotiation. Repetição com o mesmo resultado é ignorada.
     *
     * @return falso se o resultado já estava registrado
     */
    public boolean recordOutcome(Money applied, Money unapplied, Instant now) {
        Guard.requireState(kind == TargetKind.AGREEMENT, "allocation.not_agreement", "Alocação não é de acordo");
        if (outcome != AllocationOutcome.PENDING) {
            return false;
        }
        Money a = applied == null ? Money.ZERO : applied.max(Money.ZERO);
        Money u = unapplied == null ? Money.ZERO : unapplied.max(Money.ZERO).min(amount);
        this.appliedAmount = a;
        this.unappliedAmount = u;
        this.outcomeAt = now;
        this.outcome = u.isZero() ? AllocationOutcome.APPLIED : a.isZero() ? AllocationOutcome.UNAPPLIED
                : AllocationOutcome.PARTIAL;
        return true;
    }

    /** Valor não aplicado pelo negotiation ainda não realocado. */
    public Money availableForReallocation() {
        if (status != AllocationStatus.ACTIVE || unappliedAmount == null) {
            return Money.ZERO;
        }
        return unappliedAmount.minus(releasedAmount).max(Money.ZERO);
    }

    /** Libera parte não aplicada para realocação. */
    public void release(Money value) {
        Guard.require(value != null && value.isPositive() && !value.isGreaterThan(availableForReallocation()),
                "allocation.release_exceeds_unapplied", "Valor acima do não aplicado disponível nesta alocação");
        this.releasedAmount = releasedAmount.plus(value);
    }

    /** Registra o valor compensado aos consumidores por um {@code PaymentReversed}. */
    public void compensate(Money value, UUID eventId) {
        Guard.require(value != null && value.isPositive() && !compensatedAmount.plus(value).isGreaterThan(amount),
                "allocation.compensation_invalid", "Compensação acima do valor alocado");
        this.compensatedAmount = compensatedAmount.plus(value);
        this.reversalEventId = eventId;
    }

    /**
     * Estorno do pagamento: reverte a alocação.
     *
     * @return valor ainda não compensado aos consumidores (a publicar em {@code PaymentReversed})
     */
    public Money reverse(Instant now) {
        Guard.requireState(status == AllocationStatus.ACTIVE, "allocation.already_reversed", "Alocação já revertida");
        this.status = AllocationStatus.REVERSED;
        this.reversedAt = now;
        return amount.minus(compensatedAmount).max(Money.ZERO);
    }

    /** Valor que permanece alocado (descontada a parte liberada para realocação). */
    public Money net() {
        return amount.minus(releasedAmount);
    }

    /** Alocações a acordo e a títulos são publicadas aos consumidores; devoluções não. */
    public boolean publishesEvents() {
        return kind != TargetKind.REFUND;
    }

    public boolean isActive() {
        return status == AllocationStatus.ACTIVE;
    }

    private static Money sum(List<Line> lines) {
        return lines.stream().map(Line::amount).reduce(Money.ZERO, Money::plus);
    }

    /** Linha da alocação: parcela de acordo ou título e o valor. */
    @Embeddable
    public static class Line {

        @Column(name = "installment_id", updatable = false)
        private UUID installmentId;

        @Column(name = "title_id", updatable = false)
        private UUID titleId;

        @Column(name = "amount", nullable = false, updatable = false)
        private Money amount;

        protected Line() {
        }

        public Line(UUID installmentId, UUID titleId, Money amount) {
            Guard.require(installmentId != null || titleId != null, "allocation.line_target_required",
                    "Linha sem parcela nem título");
            Guard.require(amount != null && amount.isPositive(), "allocation.line_amount_invalid",
                    "Valor da linha deve ser positivo");
            this.installmentId = installmentId;
            this.titleId = titleId;
            this.amount = amount;
        }

        public static Line installment(UUID installmentId, Money amount) {
            return new Line(installmentId, null, amount);
        }

        public static Line title(UUID titleId, Money amount) {
            return new Line(null, titleId, amount);
        }

        public UUID installmentId() {
            return installmentId;
        }

        public UUID titleId() {
            return titleId;
        }

        public Money amount() {
            return amount;
        }
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public TargetKind getKind() {
        return kind;
    }

    public AllocationOrigin getOrigin() {
        return origin;
    }

    public AllocationStatus getStatus() {
        return status;
    }

    public UUID getAgreementId() {
        return agreementId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public Money getAmount() {
        return amount;
    }

    public Money getReleasedAmount() {
        return releasedAmount;
    }

    public Money getCompensatedAmount() {
        return compensatedAmount;
    }

    public AllocationOutcome getOutcome() {
        return outcome;
    }

    public Money getAppliedAmount() {
        return appliedAmount;
    }

    public Money getUnappliedAmount() {
        return unappliedAmount;
    }

    public Instant getOutcomeAt() {
        return outcomeAt;
    }

    public UUID getSourceAllocationId() {
        return sourceAllocationId;
    }

    public String getRefundReference() {
        return refundReference;
    }

    public UUID getReversalEventId() {
        return reversalEventId;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }

    public List<Line> getLines() {
        return Collections.unmodifiableList(lines);
    }
}
