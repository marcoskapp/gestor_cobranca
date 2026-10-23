package br.com.gestorcobranca.incentive.domain.model.ledger;

import br.com.gestorcobranca.incentive.domain.model.DelayBucket;
import br.com.gestorcobranca.incentive.domain.model.Segment;
import br.com.gestorcobranca.incentive.domain.model.attribution.AgreementOrigin;
import br.com.gestorcobranca.incentive.domain.model.attribution.BeneficiaryType;
import br.com.gestorcobranca.incentive.domain.model.attribution.ParticipantRole;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.Percentage;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Lançamento do razão de comissão (append-only, §5.3): crédito de recuperação a um beneficiário
 * originado de um pagamento confirmado ({@link EntryKind#ACCRUAL}) ou compensação de estorno
 * ({@link EntryKind#REVERSAL}, valores negativos). O lançamento guarda a base (parte do
 * pagamento), o peso de crédito e o fator de renegociação — a comissão em reais é apurada no
 * fechamento, porque faixas progressivas, metas e deflatores dependem do total do período.
 * <p>
 * Colunas financeiras não são atualizáveis (JPA {@code updatable = false}, privilégio por coluna
 * e gatilho no banco); só o ciclo {@code PENDING → CLEARED/WAIVED}, o período de competência e o
 * extrato são preenchidos depois.
 */
@Entity
@Table(name = "commission_ledger_entry", schema = "incentive")
public class CommissionLedgerEntry extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 10)
    private EntryKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private EntryStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "beneficiary_type", nullable = false, updatable = false, length = 10)
    private BeneficiaryType beneficiaryType;

    @Column(name = "beneficiary_id", nullable = false, updatable = false)
    private UUID beneficiaryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", updatable = false, length = 20)
    private ParticipantRole role;

    @Column(name = "attribution_id", nullable = false, updatable = false)
    private UUID attributionId;

    @Column(name = "agreement_id", nullable = false, updatable = false)
    private UUID agreementId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "reversal_id", updatable = false)
    private UUID reversalId;

    @Column(name = "reverses_entry_id", updatable = false)
    private UUID reversesEntryId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", updatable = false)
    private UUID portfolioId;

    @Enumerated(EnumType.STRING)
    @Column(name = "delay_bucket", nullable = false, updatable = false, length = 20)
    private DelayBucket delayBucket;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, updatable = false, length = 40)
    private AgreementOrigin origin;

    @Column(name = "channel", nullable = false, updatable = false, length = 20)
    private String channel;

    @Column(name = "base_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money baseAmount;

    @Column(name = "credit_weight", nullable = false, updatable = false, precision = 9, scale = 4)
    private Percentage creditWeight;

    @Column(name = "renegotiation_factor", nullable = false, updatable = false, precision = 9, scale = 4)
    private Percentage renegotiationFactor;

    /** base × peso × fator de renegociação. */
    @Column(name = "credited_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money creditedAmount;

    /** Motivo do crédito (ex.: {@code ORIGIN_WEIGHT}, {@code PORTFOLIO_MODEL}, {@code TERMINATED}). */
    @Column(name = "credit_note", nullable = false, updatable = false, length = 60)
    private String creditNote;

    @Column(name = "plan_id", updatable = false)
    private UUID planId;

    @Column(name = "paid_on", nullable = false, updatable = false)
    private LocalDate paidOn;

    @Column(name = "confirmed_on", nullable = false, updatable = false)
    private LocalDate confirmedOn;

    @Column(name = "clears_on", nullable = false, updatable = false)
    private LocalDate clearsOn;

    @Column(name = "cleared_at")
    private Instant clearedAt;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "statement_id")
    private UUID statementId;

    protected CommissionLedgerEntry() {
    }

    /** Dimensões e identificação comuns a crédito e compensação. */
    public record Source(UUID attributionId, UUID agreementId, UUID paymentId, Segment segment, AgreementOrigin origin,
                         String channel) {
    }

    /** Crédito por pagamento confirmado: {@code credited = base × peso × fator}, arredondado em centavos. */
    public static CommissionLedgerEntry accrual(Source source, BeneficiaryType beneficiaryType, UUID beneficiaryId,
                                                ParticipantRole role, Money base, Percentage weight,
                                                Percentage renegotiationFactor, String creditNote, UUID planId,
                                                LocalDate paidOn, LocalDate confirmedOn, LocalDate clearsOn) {
        Guard.require(base != null && !base.isNegative(), "ledger.base_invalid", "Base do lançamento inválida");
        CommissionLedgerEntry e = new CommissionLedgerEntry();
        e.fill(source, beneficiaryType, beneficiaryId, role);
        e.kind = EntryKind.ACCRUAL;
        e.status = EntryStatus.PENDING;
        e.baseAmount = base;
        e.creditWeight = Objects.requireNonNull(weight, "weight");
        e.renegotiationFactor = Objects.requireNonNull(renegotiationFactor, "renegotiationFactor");
        e.creditedAmount = base.times(weight.asFraction().multiply(renegotiationFactor.asFraction()));
        e.creditNote = Objects.requireNonNull(creditNote, "creditNote");
        e.planId = planId;
        e.paidOn = Objects.requireNonNull(paidOn, "paidOn");
        e.confirmedOn = Objects.requireNonNull(confirmedOn, "confirmedOn");
        e.clearsOn = Objects.requireNonNull(clearsOn, "clearsOn");
        Guard.require(!clearsOn.isBefore(confirmedOn), "ledger.clears_on_invalid", "Liberação antes da confirmação");
        return e;
    }

    /**
     * Compensação proporcional de um crédito por estorno.
     *
     * @param fraction  parte do pagamento estornada (0 &lt; fração ≤ 1)
     * @param reversedOn data do estorno (fuso de operação)
     */
    public static CommissionLedgerEntry reversalOf(CommissionLedgerEntry original, BigDecimal fraction, UUID reversalId,
                                                   LocalDate reversedOn) {
        Guard.requireState(original.kind == EntryKind.ACCRUAL, "ledger.reversal_of_reversal", "Só créditos podem ser estornados");
        Guard.require(fraction.signum() > 0 && fraction.compareTo(BigDecimal.ONE) <= 0, "ledger.reversal_fraction_invalid",
                "Fração do estorno inválida");
        CommissionLedgerEntry e = new CommissionLedgerEntry();
        e.fill(new Source(original.attributionId, original.agreementId, original.paymentId, original.segment(),
                original.origin, original.channel), original.beneficiaryType, original.beneficiaryId, original.role);
        e.kind = EntryKind.REVERSAL;
        e.reversalId = Objects.requireNonNull(reversalId, "reversalId");
        e.reversesEntryId = original.getId();
        e.baseAmount = original.baseAmount.times(fraction.setScale(10, RoundingMode.HALF_EVEN)).negate();
        e.creditedAmount = original.creditedAmount.times(fraction.setScale(10, RoundingMode.HALF_EVEN)).negate();
        e.creditWeight = original.creditWeight;
        e.renegotiationFactor = original.renegotiationFactor;
        e.creditNote = "PAYMENT_REVERSED";
        e.planId = original.planId;
        e.paidOn = original.paidOn;
        e.confirmedOn = reversedOn.isBefore(original.confirmedOn) ? original.confirmedOn : reversedOn;
        e.status = EntryStatus.PENDING;
        // crédito ainda em carência: a compensação é liberada junto com ele (anulam-se no mesmo período);
        // crédito já liberado: a compensação é liberada de imediato, no período decidido pela aplicação
        e.clearsOn = original.status == EntryStatus.PENDING && original.clearsOn.isAfter(e.confirmedOn)
                ? original.clearsOn : e.confirmedOn;
        return e;
    }

    private void fill(Source source, BeneficiaryType beneficiaryType, UUID beneficiaryId, ParticipantRole role) {
        this.attributionId = Objects.requireNonNull(source.attributionId(), "attributionId");
        this.agreementId = Objects.requireNonNull(source.agreementId(), "agreementId");
        this.paymentId = Objects.requireNonNull(source.paymentId(), "paymentId");
        this.creditorId = source.segment().creditorId();
        this.portfolioId = source.segment().portfolioId();
        this.delayBucket = source.segment().delayBucket();
        this.origin = Objects.requireNonNull(source.origin(), "origin");
        this.channel = source.channel() == null ? "UNKNOWN" : source.channel();
        this.beneficiaryType = Objects.requireNonNull(beneficiaryType, "beneficiaryType");
        this.beneficiaryId = Objects.requireNonNull(beneficiaryId, "beneficiaryId");
        this.role = role;
    }

    /** Libera o lançamento para apuração no período de competência informado. */
    public void clear(LocalDate periodStart, Instant now) {
        Guard.requireState(status == EntryStatus.PENDING, "ledger.not_pending", "Lançamento não está em carência");
        Guard.require(periodStart != null && periodStart.getDayOfMonth() == 1, "ledger.period_invalid", "Período inválido");
        this.status = EntryStatus.CLEARED;
        this.clearedAt = now;
        this.periodStart = periodStart;
    }

    /** Estorno após o fechamento sem estorno de comissão habilitado: registrado, sem efeito no extrato. */
    public void waive() {
        Guard.requireState(status == EntryStatus.PENDING && kind == EntryKind.REVERSAL, "ledger.cannot_waive",
                "Somente compensação em carência pode ser dispensada");
        this.status = EntryStatus.WAIVED;
    }

    public void includeIn(UUID statementId) {
        Guard.requireState(status == EntryStatus.CLEARED, "ledger.not_cleared", "Lançamento não liberado");
        Guard.requireState(this.statementId == null || this.statementId.equals(statementId), "ledger.already_in_statement",
                "Lançamento já incluído em outro extrato");
        this.statementId = statementId;
    }

    public Segment segment() {
        return new Segment(creditorId, portfolioId, delayBucket);
    }

    public boolean isPending() {
        return status == EntryStatus.PENDING;
    }

    public EntryKind getKind() {
        return kind;
    }

    public EntryStatus getStatus() {
        return status;
    }

    public BeneficiaryType getBeneficiaryType() {
        return beneficiaryType;
    }

    public UUID getBeneficiaryId() {
        return beneficiaryId;
    }

    public ParticipantRole getRole() {
        return role;
    }

    public UUID getAttributionId() {
        return attributionId;
    }

    public UUID getAgreementId() {
        return agreementId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getReversalId() {
        return reversalId;
    }

    public UUID getReversesEntryId() {
        return reversesEntryId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public DelayBucket getDelayBucket() {
        return delayBucket;
    }

    public AgreementOrigin getOrigin() {
        return origin;
    }

    public String getChannel() {
        return channel;
    }

    public Money getBaseAmount() {
        return baseAmount;
    }

    public Percentage getCreditWeight() {
        return creditWeight;
    }

    public Percentage getRenegotiationFactor() {
        return renegotiationFactor;
    }

    public Money getCreditedAmount() {
        return creditedAmount;
    }

    public String getCreditNote() {
        return creditNote;
    }

    public UUID getPlanId() {
        return planId;
    }

    public LocalDate getPaidOn() {
        return paidOn;
    }

    public LocalDate getConfirmedOn() {
        return confirmedOn;
    }

    public LocalDate getClearsOn() {
        return clearsOn;
    }

    public Instant getClearedAt() {
        return clearedAt;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public UUID getStatementId() {
        return statementId;
    }
}
