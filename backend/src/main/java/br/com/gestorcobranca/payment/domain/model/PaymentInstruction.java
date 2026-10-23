package br.com.gestorcobranca.payment.domain.model;

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
import java.util.UUID;

/**
 * Instrução de pagamento (F10) para UMA parcela de acordo ou UM título avulso: Pix imediato
 * ({@code cob}), Pix com vencimento ({@code cobv}), boleto ou link. Nasce {@code PENDING} e é
 * registrada no provedor de forma assíncrona; ativa, traz o {@code txid}, a localização e o
 * "copia e cola" (BR Code, conteúdo do QR Code). Paga, expirada, cancelada ou recusada pelo provedor
 * encerra o ciclo.
 */
@Entity
@Table(name = "payment_instruction", schema = "payment")
public class PaymentInstruction extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private InstructionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InstructionStatus status;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", updatable = false)
    private UUID portfolioId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "case_id", updatable = false)
    private UUID caseId;

    @Column(name = "agreement_id", updatable = false)
    private UUID agreementId;

    @Column(name = "installment_id", updatable = false)
    private UUID installmentId;

    @Column(name = "installment_number", updatable = false)
    private Integer installmentNumber;

    @Column(name = "title_id", updatable = false)
    private UUID titleId;

    @Column(name = "amount", nullable = false, updatable = false)
    private Money amount;

    @Column(name = "due_date", updatable = false)
    private LocalDate dueDate;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "pix_account_id", updatable = false)
    private UUID pixAccountId;

    @Column(name = "txid", length = 35, updatable = false)
    private String txid;

    @Column(name = "provider_revision")
    private Integer providerRevision;

    @Column(name = "location", length = 300)
    private String location;

    @Column(name = "pix_copy_paste", length = 1000)
    private String pixCopyPaste;

    @Column(name = "registered_at")
    private Instant registeredAt;

    @Column(name = "failure_code", length = 60)
    private String failureCode;

    @Column(name = "failure_detail", length = 300)
    private String failureDetail;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 200)
    private String cancelReason;

    @Column(name = "channel", length = 20, updatable = false)
    private String channel;

    protected PaymentInstruction() {
    }

    private PaymentInstruction(InstructionType type, UUID creditorId, UUID portfolioId, UUID debtorId, Money amount,
                               LocalDate dueDate, Instant expiresAt, UUID pixAccountId, String txid, String channel,
                               Instant now) {
        Guard.require(type != null, "payment_instruction.type_required", "Tipo da instrução obrigatório");
        Guard.require(creditorId != null && debtorId != null, "payment_instruction.target_required",
                "Credor e devedor obrigatórios");
        Guard.require(amount != null && amount.isPositive(), "payment_instruction.amount_invalid",
                "Valor da instrução deve ser positivo");
        Guard.require(expiresAt != null && expiresAt.isAfter(now), "payment_instruction.expiration_invalid",
                "Expiração deve ser futura");
        Guard.require(type != InstructionType.PIX_DUE || dueDate != null, "payment_instruction.due_date_required",
                "Pix com vencimento exige data de vencimento");
        Guard.require(!isPix(type) || (pixAccountId != null && txid != null), "payment_instruction.pix_account_required",
                "Instrução Pix exige conta Pix e txid");
        Guard.require(channel == null || channel.length() <= 20, "payment_instruction.channel_invalid", "Canal inválido");
        this.type = type;
        this.status = InstructionStatus.PENDING;
        this.creditorId = creditorId;
        this.portfolioId = portfolioId;
        this.debtorId = debtorId;
        this.amount = amount;
        this.dueDate = dueDate;
        this.expiresAt = expiresAt;
        this.pixAccountId = pixAccountId;
        this.txid = txid;
        this.channel = channel;
    }

    public static PaymentInstruction forInstallment(InstructionType type, UUID creditorId, UUID portfolioId, UUID debtorId,
                                                    UUID caseId, UUID agreementId, UUID installmentId,
                                                    int installmentNumber, Money amount, LocalDate dueDate,
                                                    Instant expiresAt, UUID pixAccountId, String txid, String channel,
                                                    Instant now) {
        Guard.require(agreementId != null && installmentId != null, "payment_instruction.installment_required",
                "Acordo e parcela obrigatórios");
        PaymentInstruction i = new PaymentInstruction(type, creditorId, portfolioId, debtorId, amount, dueDate, expiresAt,
                pixAccountId, txid, channel, now);
        i.caseId = caseId;
        i.agreementId = agreementId;
        i.installmentId = installmentId;
        i.installmentNumber = installmentNumber;
        return i;
    }

    public static PaymentInstruction forTitle(InstructionType type, UUID creditorId, UUID portfolioId, UUID debtorId,
                                              UUID titleId, Money amount, LocalDate dueDate, Instant expiresAt,
                                              UUID pixAccountId, String txid, String channel, Instant now) {
        Guard.require(titleId != null, "payment_instruction.title_required", "Título obrigatório");
        PaymentInstruction i = new PaymentInstruction(type, creditorId, portfolioId, debtorId, amount, dueDate, expiresAt,
                pixAccountId, txid, channel, now);
        i.titleId = titleId;
        return i;
    }

    /** Registro aceito pelo provedor. */
    public void activate(Integer revision, String location, String copyPaste, Instant now) {
        Guard.requireState(status == InstructionStatus.PENDING, "payment_instruction.not_pending",
                "Instrução não está aguardando registro");
        Guard.require(copyPaste != null && !copyPaste.isBlank() && copyPaste.length() <= 1000,
                "payment_instruction.copy_paste_invalid", "Provedor não devolveu o código copia e cola");
        this.status = InstructionStatus.ACTIVE;
        this.providerRevision = revision;
        this.location = location == null ? null : truncate(location, 300);
        this.pixCopyPaste = copyPaste;
        this.registeredAt = now;
    }

    /** Recusa definitiva do provedor. */
    public void fail(String code, String detail) {
        Guard.requireState(status == InstructionStatus.PENDING, "payment_instruction.not_pending",
                "Instrução não está aguardando registro");
        this.status = InstructionStatus.FAILED;
        this.failureCode = truncate(code == null ? "PROVIDER_REJECTED" : code, 60);
        this.failureDetail = detail == null ? null : truncate(detail, 300);
    }

    /**
     * Pagamento recebido para a instrução (mesmo expirada ou cancelada: o dinheiro entrou).
     *
     * @return falso se já estava paga
     */
    public boolean markPaid(UUID paymentId, Instant paidAt) {
        if (status == InstructionStatus.PAID) {
            return false;
        }
        this.status = InstructionStatus.PAID;
        this.paymentId = paymentId;
        this.paidAt = paidAt;
        return true;
    }

    /** @return verdadeiro se estava registrada no provedor (precisa de cancelamento lá) */
    public boolean cancel(String reason, Instant now) {
        Guard.requireState(status.isOpen(), "payment_instruction.not_open", "Instrução não está em aberto");
        Guard.require(reason != null && !reason.isBlank() && reason.length() <= 200,
                "payment_instruction.cancel_reason_required", "Informe o motivo do cancelamento (até 200 caracteres)");
        boolean registered = status == InstructionStatus.ACTIVE;
        this.status = InstructionStatus.CANCELLED;
        this.cancelledAt = now;
        this.cancelReason = reason.trim();
        return registered;
    }

    /** @return verdadeiro se expirou agora */
    public boolean expireIfDue(Instant now) {
        if (status.isOpen() && !expiresAt.isAfter(now)) {
            this.status = InstructionStatus.EXPIRED;
            return true;
        }
        return false;
    }

    public boolean isPix() {
        return isPix(type);
    }

    public static boolean isPix(InstructionType type) {
        return type == InstructionType.PIX_IMMEDIATE || type == InstructionType.PIX_DUE;
    }

    public TargetKind targetKind() {
        return agreementId != null ? TargetKind.AGREEMENT : TargetKind.TITLES;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    public InstructionType getType() {
        return type;
    }

    public InstructionStatus getStatus() {
        return status;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getAgreementId() {
        return agreementId;
    }

    public UUID getInstallmentId() {
        return installmentId;
    }

    public Integer getInstallmentNumber() {
        return installmentNumber;
    }

    public UUID getTitleId() {
        return titleId;
    }

    public Money getAmount() {
        return amount;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getPixAccountId() {
        return pixAccountId;
    }

    public String getTxid() {
        return txid;
    }

    public Integer getProviderRevision() {
        return providerRevision;
    }

    public String getLocation() {
        return location;
    }

    public String getPixCopyPaste() {
        return pixCopyPaste;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureDetail() {
        return failureDetail;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public String getChannel() {
        return channel;
    }
}
