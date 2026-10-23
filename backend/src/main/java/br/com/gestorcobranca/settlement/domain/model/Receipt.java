package br.com.gestorcobranca.settlement.domain.model;

import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules.ReceiptProfile;
import br.com.gestorcobranca.settlement.domain.model.rules.PaymentType;
import br.com.gestorcobranca.settlement.domain.model.rules.ReceivedBy;
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
import java.util.UUID;

/**
 * Recebimento: recuperação financeira confirmada (RB08) de uma alocação do payment ({@code paymentId}
 * dos eventos). Título avulso → um recebimento por título; acordo → um recebimento com o valor que o
 * negotiation aplicou (acordo não pago ou valor não aplicado não é recuperação). Estorno e realocação
 * geram recebimentos {@code REVERSAL} negativos, ligados ao original, na data do estorno — se o
 * original já foi prestado contas, a compensação entra no demonstrativo seguinte.
 * <p>
 * Fatos imutáveis (gatilho no banco); mudam só a apuração, o contrato resolvido e o vínculo com o
 * demonstrativo.
 */
@Entity
@Table(name = "receipt", schema = "settlement")
public class Receipt extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 10)
    private ReceiptKind kind;
    @Column(name = "allocation_id", nullable = false, updatable = false)
    private UUID allocationId;
    @Column(name = "payment_id", updatable = false)
    private UUID paymentId;
    @Column(name = "title_id", updatable = false)
    private UUID titleId;
    @Column(name = "title_reference", updatable = false, length = 120)
    private String titleReference;
    @Column(name = "reversal_id", updatable = false)
    private UUID reversalId;
    @Column(name = "reverses_receipt_id", updatable = false)
    private UUID reversesReceiptId;
    @Column(name = "reversal_reason", updatable = false, length = 40)
    private String reversalReason;
    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;
    @Column(name = "portfolio_id", updatable = false)
    private UUID portfolioId;
    @Column(name = "contract_id")
    private UUID contractId;
    @Column(name = "agreement_id", updatable = false)
    private UUID agreementId;
    @Column(name = "debtor_id", updatable = false)
    private UUID debtorId;
    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money amount;
    @Column(name = "honorarium_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money honorariumAmount;
    @Column(name = "days_past_due", updatable = false)
    private Integer daysPastDue;
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_type", nullable = false, updatable = false, length = 20)
    private PaymentType paymentType;
    @Enumerated(EnumType.STRING)
    @Column(name = "received_by", nullable = false, updatable = false, length = 10)
    private ReceivedBy receivedBy;
    @Column(name = "renegotiation", nullable = false, updatable = false)
    private boolean renegotiation;
    @Column(name = "method", updatable = false, length = 20)
    private String method;
    @Column(name = "source", updatable = false, length = 20)
    private String source;
    @Column(name = "paid_on", nullable = false, updatable = false)
    private LocalDate paidOn;
    @Column(name = "competence_date", nullable = false, updatable = false)
    private LocalDate competenceDate;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", nullable = false, updatable = false)
    private String detail;
    @Enumerated(EnumType.STRING)
    @Column(name = "rating_status", nullable = false, length = 20)
    private RatingStatus ratingStatus;
    @Column(name = "pending_reason", length = 40)
    private String pendingReason;
    @Column(name = "rated_at")
    private Instant ratedAt;
    @Column(name = "statement_id")
    private UUID statementId;

    protected Receipt() {
    }

    /**
     * Dados de um recebimento confirmado.
     *
     * @param paymentId  pagamento dono da alocação no payment (conciliação)
     * @param detailJson parcelas, títulos e componentes (memória para a exportação conciliável)
     */
    public record PaymentData(UUID allocationId, UUID paymentId, UUID titleId, String titleReference, UUID creditorId,
                              UUID portfolioId, UUID contractId, UUID agreementId, UUID debtorId, Money amount,
                              Money honorarium, Integer daysPastDue, PaymentType paymentType, ReceivedBy receivedBy,
                              boolean renegotiation, String method, String source, LocalDate paidOn, String detailJson) {
    }

    public static Receipt payment(PaymentData d) {
        Guard.require(d.allocationId() != null && d.creditorId() != null && d.paidOn() != null,
                "receipt.invalid", "Alocação, credor e data do pagamento obrigatórios");
        Guard.require(d.amount() != null && d.amount().isPositive(), "receipt.amount_invalid",
                "Recebimento deve ser positivo");
        Money honorarium = d.honorarium() == null ? Money.ZERO : d.honorarium().max(Money.ZERO).min(d.amount());
        Receipt r = new Receipt();
        r.kind = ReceiptKind.PAYMENT;
        r.allocationId = d.allocationId();
        r.paymentId = d.paymentId();
        r.titleId = d.titleId();
        r.titleReference = truncate(d.titleReference(), 120);
        r.creditorId = d.creditorId();
        r.portfolioId = d.portfolioId();
        r.contractId = d.contractId();
        r.agreementId = d.agreementId();
        r.debtorId = d.debtorId();
        r.amount = d.amount();
        r.honorariumAmount = honorarium;
        r.daysPastDue = d.daysPastDue();
        r.paymentType = d.paymentType();
        r.receivedBy = d.receivedBy();
        r.renegotiation = d.renegotiation();
        r.method = truncate(d.method(), 20);
        r.source = truncate(d.source(), 20);
        r.paidOn = d.paidOn();
        r.competenceDate = d.paidOn();
        r.detail = d.detailJson() == null ? "{}" : d.detailJson();
        r.ratingStatus = RatingStatus.PENDING_RULES;
        r.pendingReason = "NOT_RATED";
        return r;
    }

    /**
     * Compensação de estorno deste recebimento.
     *
     * @param reversed parte estornada (positiva, até o saldo líquido do recebimento)
     */
    public Receipt reversal(Money reversed, Money honorariumReversed, UUID reversalId, String reason, LocalDate reversedOn) {
        Guard.requireState(kind == ReceiptKind.PAYMENT, "receipt.not_payment", "Só recebimento confirmado é estornado");
        Guard.require(reversed != null && reversed.isPositive() && !reversed.isGreaterThan(amount),
                "receipt.reversal_invalid", "Estorno inválido");
        Receipt r = new Receipt();
        r.kind = ReceiptKind.REVERSAL;
        r.allocationId = allocationId;
        r.paymentId = paymentId;
        r.titleId = titleId;
        r.titleReference = titleReference;
        r.reversalId = reversalId;
        r.reversesReceiptId = getId();
        r.reversalReason = truncate(reason, 40);
        r.creditorId = creditorId;
        r.portfolioId = portfolioId;
        r.contractId = contractId;
        r.agreementId = agreementId;
        r.debtorId = debtorId;
        r.amount = reversed.negate();
        r.honorariumAmount = honorariumReversed.max(Money.ZERO).min(reversed).negate();
        r.daysPastDue = daysPastDue;
        r.paymentType = paymentType;
        r.receivedBy = receivedBy;
        r.renegotiation = renegotiation;
        r.method = method;
        r.source = source;
        r.paidOn = paidOn;
        r.competenceDate = reversedOn.isBefore(paidOn) ? paidOn : reversedOn;
        r.detail = "{}";
        r.ratingStatus = RatingStatus.PENDING_RULES;
        r.pendingReason = "NOT_RATED";
        return r;
    }

    public ReceiptProfile profile() {
        return new ReceiptProfile(portfolioId, daysPastDue, paymentType, receivedBy, renegotiation);
    }

    public void resolveContract(UUID contract) {
        if (this.contractId == null && contract != null) {
            this.contractId = contract;
        }
    }

    public void markPending(String reason) {
        Guard.requireState(ratingStatus == RatingStatus.PENDING_RULES, "receipt.already_rated", "Recebimento já apurado");
        this.pendingReason = reason;
    }

    public void markRated(Instant at) {
        Guard.requireState(ratingStatus == RatingStatus.PENDING_RULES, "receipt.already_rated", "Recebimento já apurado");
        this.ratingStatus = RatingStatus.RATED;
        this.pendingReason = null;
        this.ratedAt = at;
    }

    public void bindTo(UUID statement) {
        Guard.requireState(ratingStatus == RatingStatus.RATED, "receipt.not_rated", "Recebimento não apurado");
        Guard.requireState(statementId == null || statementId.equals(statement), "receipt.already_in_statement",
                "Recebimento já incluído em outro demonstrativo");
        this.statementId = statement;
    }

    public void unbind() {
        this.statementId = null;
    }

    public boolean isPayment() {
        return kind == ReceiptKind.PAYMENT;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public ReceiptKind getKind() { return kind; }
    public UUID getAllocationId() { return allocationId; }
    public UUID getPaymentId() { return paymentId; }
    public UUID getTitleId() { return titleId; }
    public String getTitleReference() { return titleReference; }
    public UUID getReversalId() { return reversalId; }
    public UUID getReversesReceiptId() { return reversesReceiptId; }
    public String getReversalReason() { return reversalReason; }
    public UUID getCreditorId() { return creditorId; }
    public UUID getPortfolioId() { return portfolioId; }
    public UUID getContractId() { return contractId; }
    public UUID getAgreementId() { return agreementId; }
    public UUID getDebtorId() { return debtorId; }
    public Money getAmount() { return amount; }
    public Money getHonorariumAmount() { return honorariumAmount; }
    public Integer getDaysPastDue() { return daysPastDue; }
    public PaymentType getPaymentType() { return paymentType; }
    public ReceivedBy getReceivedBy() { return receivedBy; }
    public boolean isRenegotiation() { return renegotiation; }
    public String getMethod() { return method; }
    public String getSource() { return source; }
    public LocalDate getPaidOn() { return paidOn; }
    public LocalDate getCompetenceDate() { return competenceDate; }
    public String getDetail() { return detail; }
    public RatingStatus getRatingStatus() { return ratingStatus; }
    public String getPendingReason() { return pendingReason; }
    public Instant getRatedAt() { return ratedAt; }
    public UUID getStatementId() { return statementId; }
}
