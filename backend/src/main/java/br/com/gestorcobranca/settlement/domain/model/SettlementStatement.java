package br.com.gestorcobranca.settlement.domain.model;

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
import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Demonstrativo de prestação de contas de um credor num período (F17, F22). Calculado a partir dos
 * recebimentos apurados e despesas ainda não prestados com competência até o fim do período (os que
 * chegarem depois entram no seguinte), dos ajustes e das informações do período (valor negociado,
 * comissão prevista, pendências de conciliação e de regra, divergências abertas). Segregação: quem
 * calculou ou pediu ajuste não aprova. Aprovado é imutável (gatilho no banco); correções entram no
 * período seguinte.
 */
@Entity
@Table(name = "statement", schema = "settlement")
public class SettlementStatement extends TenantScopedEntity {

    public static final int CALCULATION_SCHEMA = 1;
    private static final Set<String> CHANNELS = Set.of("EMAIL", "PORTAL", "API", "LETTER", "OTHER");

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;
    @Column(name = "sequence_number", nullable = false, updatable = false)
    private int sequenceNumber;
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;
    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StatementStatus status;
    @Column(name = "received_agency_amount", nullable = false, precision = 19, scale = 2)
    private Money receivedAgencyAmount;
    @Column(name = "received_creditor_amount", nullable = false, precision = 19, scale = 2)
    private Money receivedCreditorAmount;
    @Column(name = "reversed_amount", nullable = false, precision = 19, scale = 2)
    private Money reversedAmount;
    @Column(name = "recovered_amount", nullable = false, precision = 19, scale = 2)
    private Money recoveredAmount;
    @Column(name = "reconciled_amount", nullable = false, precision = 19, scale = 2)
    private Money reconciledAmount;
    @Column(name = "agency_honorarium", nullable = false, precision = 19, scale = 2)
    private Money agencyHonorarium;
    @Column(name = "creditor_honorarium", nullable = false, precision = 19, scale = 2)
    private Money creditorHonorarium;
    @Column(name = "commission_amount", nullable = false, precision = 19, scale = 2)
    private Money commissionAmount;
    @Column(name = "fixed_fee_amount", nullable = false, precision = 19, scale = 2)
    private Money fixedFeeAmount;
    @Column(name = "expenses_amount", nullable = false, precision = 19, scale = 2)
    private Money expensesAmount;
    @Column(name = "adjustments_amount", nullable = false, precision = 19, scale = 2)
    private Money adjustmentsAmount;
    @Column(name = "net_transfer_amount", nullable = false, precision = 19, scale = 2)
    private Money netTransferAmount;
    @Column(name = "transferred_amount", nullable = false, precision = 19, scale = 2)
    private Money transferredAmount;
    @Column(name = "negotiated_amount", nullable = false, precision = 19, scale = 2)
    private Money negotiatedAmount;
    @Column(name = "forecast_commission", nullable = false, precision = 19, scale = 2)
    private Money forecastCommission;
    @Column(name = "pending_reconciliation_amount", nullable = false, precision = 19, scale = 2)
    private Money pendingReconciliationAmount;
    @Column(name = "pending_rules_amount", nullable = false, precision = 19, scale = 2)
    private Money pendingRulesAmount;
    @Column(name = "open_divergences", nullable = false)
    private int openDivergences;
    @Column(name = "receipts_count", nullable = false)
    private int receiptsCount;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "calculation")
    private String calculation;
    @Column(name = "calculation_schema", nullable = false)
    private int calculationSchema;
    @Column(name = "calculated_at")
    private Instant calculatedAt;
    @Column(name = "calculated_by")
    private UUID calculatedBy;
    @Column(name = "reviewed_at")
    private Instant reviewedAt;
    @Column(name = "reviewed_by")
    private UUID reviewedBy;
    @Column(name = "approved_at")
    private Instant approvedAt;
    @Column(name = "approved_by")
    private UUID approvedBy;
    @Column(name = "sent_at")
    private Instant sentAt;
    @Column(name = "sent_by")
    private UUID sentBy;
    @Column(name = "sent_channel", length = 20)
    private String sentChannel;
    @Column(name = "sent_reference", length = 200)
    private String sentReference;
    @Column(name = "creditor_response_at")
    private Instant creditorResponseAt;
    @Column(name = "creditor_response_by")
    private UUID creditorResponseBy;
    @Column(name = "creditor_response_note", length = 2000)
    private String creditorResponseNote;
    @Column(name = "cancelled_reason", length = 500)
    private String cancelledReason;

    protected SettlementStatement() {
    }

    public SettlementStatement(UUID creditorId, int sequenceNumber, LocalDate periodStart, LocalDate periodEnd) {
        Guard.require(creditorId != null, "statement.creditor_required", "Credor obrigatório");
        Guard.require(periodStart != null && periodEnd != null && !periodEnd.isBefore(periodStart),
                "statement.period_invalid", "Período inválido");
        Guard.require(!periodEnd.isAfter(periodStart.plusMonths(3)), "statement.period_too_long",
                "Período da prestação de contas de até 3 meses");
        this.creditorId = creditorId;
        this.sequenceNumber = sequenceNumber;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.status = StatementStatus.DRAFT;
        this.calculationSchema = CALCULATION_SCHEMA;
        apply(Totals.zero());
        this.transferredAmount = Money.ZERO;
    }

    /** Totais calculados (valores positivos, exceto estornos, ajustes e líquido, que têm sinal). */
    public record Totals(Money receivedAgency, Money receivedCreditor, Money reversed, Money recovered, Money reconciled,
                         Money agencyHonorarium, Money creditorHonorarium, Money commission, Money fixedFee,
                         Money expenses, Money adjustments, Money netTransfer, Money negotiated, Money forecastCommission,
                         Money pendingReconciliation, Money pendingRules, int openDivergences, int receipts) {

        public static Totals zero() {
            return new Totals(Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO,
                    Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO,
                    Money.ZERO, 0, 0);
        }
    }

    public void calculated(Totals totals, String calculationJson, UUID by, Instant at, LocalDate today) {
        Guard.requireState(status.isOpen(), "statement.not_open", "Demonstrativo não está em aberto");
        Guard.require(periodEnd.isBefore(today), "statement.period_not_ended",
                "O período só pode ser calculado depois de encerrado");
        apply(totals);
        this.calculation = calculationJson;
        this.calculatedAt = at;
        this.calculatedBy = by;
        this.reviewedAt = null;
        this.reviewedBy = null;
        this.status = StatementStatus.CALCULATED;
    }

    /** Ajustes aplicados mudaram: o líquido é refeito e a revisão volta a ser exigida. */
    public void adjustmentsChanged(Money adjustments) {
        Guard.requireState(status == StatementStatus.CALCULATED || status == StatementStatus.REVIEWED,
                "statement.not_calculated", "Demonstrativo precisa estar calculado");
        this.netTransferAmount = netTransferAmount.minus(adjustmentsAmount).plus(adjustments);
        this.adjustmentsAmount = adjustments;
        this.reviewedAt = null;
        this.reviewedBy = null;
        this.status = StatementStatus.CALCULATED;
    }

    public void review(UUID by, Instant at) {
        Guard.requireState(status == StatementStatus.CALCULATED, "statement.not_calculated",
                "Somente demonstrativo calculado é revisado");
        this.reviewedBy = by;
        this.reviewedAt = at;
        this.status = StatementStatus.REVIEWED;
    }

    /** @param adjustmentRequesters quem pediu ajustes aplicados neste demonstrativo */
    public void approve(UUID by, Instant at, Collection<UUID> adjustmentRequesters) {
        Guard.requireState(status == StatementStatus.REVIEWED, "statement.not_reviewed",
                "Somente demonstrativo revisado é aprovado");
        Guard.require(!by.equals(calculatedBy), "statement.self_approval",
                "Quem calculou o demonstrativo não pode aprová-lo");
        Guard.require(!adjustmentRequesters.contains(by), "statement.self_approval",
                "Quem pediu ajuste no demonstrativo não pode aprová-lo");
        this.approvedBy = by;
        this.approvedAt = at;
        this.status = StatementStatus.APPROVED;
    }

    public void send(UUID by, String channel, String reference, Instant at) {
        Guard.requireState(status == StatementStatus.APPROVED, "statement.not_approved",
                "Somente demonstrativo aprovado é enviado");
        Guard.require(channel != null && CHANNELS.contains(channel), "statement.channel_invalid",
                "Canal de envio: EMAIL, PORTAL, API, LETTER ou OTHER");
        Guard.require(reference == null || reference.length() <= 200, "statement.reference_too_long",
                "Referência do envio até 200 caracteres");
        this.sentBy = by;
        this.sentAt = at;
        this.sentChannel = channel;
        this.sentReference = reference == null || reference.isBlank() ? null : reference.trim();
        this.status = StatementStatus.SENT;
    }

    public void acknowledge(UUID by, String note, Instant at) {
        Guard.requireState(status == StatementStatus.SENT || status == StatementStatus.CONTESTED,
                "statement.not_sent", "Somente demonstrativo enviado (ou contestado) recebe o aceite do credor");
        Guard.require(note == null || note.length() <= 2000, "statement.note_too_long", "Observação até 2000 caracteres");
        respond(by, note, at);
        this.status = StatementStatus.ACKNOWLEDGED;
    }

    public void contest(UUID by, String reason, Instant at) {
        Guard.requireState(status == StatementStatus.SENT, "statement.not_sent",
                "Somente demonstrativo enviado pode ser contestado");
        Guard.require(reason != null && !reason.isBlank() && reason.length() <= 2000, "statement.contest_reason_required",
                "Informe o motivo da contestação do credor (até 2000 caracteres)");
        respond(by, reason.trim(), at);
        this.status = StatementStatus.CONTESTED;
    }

    private void respond(UUID by, String note, Instant at) {
        this.creditorResponseBy = by;
        this.creditorResponseAt = at;
        this.creditorResponseNote = note == null || note.isBlank() ? creditorResponseNote : note.trim();
    }

    public void cancel(String reason) {
        Guard.requireState(status.isOpen(), "statement.not_open", "Somente demonstrativo em aberto é cancelado");
        Guard.require(reason != null && !reason.isBlank() && reason.length() <= 500, "statement.cancel_reason_required",
                "Informe o motivo do cancelamento (até 500 caracteres)");
        this.cancelledReason = reason.trim();
        this.status = StatementStatus.CANCELLED;
    }

    /** Saldo ainda não repassado (valor absoluto do líquido menos o já repassado). */
    public Money outstanding() {
        return netTransferAmount.abs().minus(transferredAmount);
    }

    public void transferRegistered(Money amount) {
        Guard.requireState(status.isClosed(), "statement.not_approved", "Repasse exige demonstrativo aprovado");
        Guard.require(amount != null && amount.isPositive() && !amount.isGreaterThan(outstanding()),
                "transfer.amount_exceeds_outstanding", "Valor acima do saldo a repassar do demonstrativo");
        this.transferredAmount = transferredAmount.plus(amount);
    }

    public void transferCancelled(Money amount) {
        this.transferredAmount = transferredAmount.minus(amount).max(Money.ZERO);
    }

    private void apply(Totals t) {
        this.receivedAgencyAmount = t.receivedAgency();
        this.receivedCreditorAmount = t.receivedCreditor();
        this.reversedAmount = t.reversed();
        this.recoveredAmount = t.recovered();
        this.reconciledAmount = t.reconciled();
        this.agencyHonorarium = t.agencyHonorarium();
        this.creditorHonorarium = t.creditorHonorarium();
        this.commissionAmount = t.commission();
        this.fixedFeeAmount = t.fixedFee();
        this.expensesAmount = t.expenses();
        this.adjustmentsAmount = t.adjustments();
        this.netTransferAmount = t.netTransfer();
        this.negotiatedAmount = t.negotiated();
        this.forecastCommission = t.forecastCommission();
        this.pendingReconciliationAmount = t.pendingReconciliation();
        this.pendingRulesAmount = t.pendingRules();
        this.openDivergences = t.openDivergences();
        this.receiptsCount = t.receipts();
    }

    public Totals totals() {
        return new Totals(receivedAgencyAmount, receivedCreditorAmount, reversedAmount, recoveredAmount,
                reconciledAmount, agencyHonorarium, creditorHonorarium, commissionAmount, fixedFeeAmount, expensesAmount,
                adjustmentsAmount, netTransferAmount, negotiatedAmount, forecastCommission, pendingReconciliationAmount,
                pendingRulesAmount, openDivergences, receiptsCount);
    }

    public UUID getCreditorId() { return creditorId; }
    public int getSequenceNumber() { return sequenceNumber; }
    public LocalDate getPeriodStart() { return periodStart; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public StatementStatus getStatus() { return status; }
    public Money getNetTransferAmount() { return netTransferAmount; }
    public Money getTransferredAmount() { return transferredAmount; }
    public Money getAdjustmentsAmount() { return adjustmentsAmount; }
    public String getCalculation() { return calculation; }
    public int getCalculationSchema() { return calculationSchema; }
    public Instant getCalculatedAt() { return calculatedAt; }
    public UUID getCalculatedBy() { return calculatedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public UUID getReviewedBy() { return reviewedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public UUID getApprovedBy() { return approvedBy; }
    public Instant getSentAt() { return sentAt; }
    public UUID getSentBy() { return sentBy; }
    public String getSentChannel() { return sentChannel; }
    public String getSentReference() { return sentReference; }
    public Instant getCreditorResponseAt() { return creditorResponseAt; }
    public UUID getCreditorResponseBy() { return creditorResponseBy; }
    public String getCreditorResponseNote() { return creditorResponseNote; }
    public String getCancelledReason() { return cancelledReason; }
}
