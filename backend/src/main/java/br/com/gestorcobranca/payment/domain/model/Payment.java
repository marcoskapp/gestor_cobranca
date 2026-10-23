package br.com.gestorcobranca.payment.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Pagamento recebido (RB06). Integrado ({@link PaymentSource#PROVIDER}: notificação do PSP
 * confirmada por consulta, ou linha de arquivo de retorno) ou externo ({@link PaymentSource#EXTERNAL}:
 * informado por um operador, com evidência obrigatória e revisão conforme o risco).
 * <p>
 * Estados: {@code INFORMED → CONFIRMED → RECONCILED}, {@code INFORMED → REJECTED},
 * {@code CONFIRMED/RECONCILED → REVERSED}. O destino solicitado ({@code target*}) é aplicado na
 * confirmação pelas alocações ({@link PaymentAllocation}); o que não couber fica como saldo não
 * alocado ({@link #unallocated()}) para realocação.
 */
@Entity
@Table(name = "payment", schema = "payment")
public class Payment extends TenantScopedEntity {

    public static final int NOTE_MAX = 1000;
    public static final int REFERENCE_MAX = 100;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20, updatable = false)
    private PaymentSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 20, updatable = false)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "amount", nullable = false, updatable = false)
    private Money amount;

    @Column(name = "paid_at", nullable = false, updatable = false)
    private Instant paidAt;

    @Column(name = "instruction_id", updatable = false)
    private UUID instructionId;

    @Column(name = "provider_transaction_id", length = 64)
    private String providerTransactionId;

    @Column(name = "external_reference", length = 100, updatable = false)
    private String externalReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "external_origin", length = 30, updatable = false)
    private ExternalOrigin externalOrigin;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_kind", length = 20, updatable = false)
    private TargetKind targetKind;

    @Column(name = "target_agreement_id", updatable = false)
    private UUID targetAgreementId;

    /** Ids (parcelas ou títulos) do destino solicitado, em ordem de preferência (JSON). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_items", updatable = false)
    private String targetItems;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_strategy", length = 20, updatable = false)
    private TitleStrategy targetStrategy;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "note_encrypted", length = 4000, updatable = false)
    private String note;

    @Column(name = "informed_by", updatable = false)
    private UUID informedBy;

    @Column(name = "informed_at", updatable = false)
    private Instant informedAt;

    @Column(name = "review_required", nullable = false, updatable = false)
    private boolean reviewRequired;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_mode", nullable = false, length = 20)
    private ReviewMode reviewMode;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "review_note_encrypted", length = 4000)
    private String reviewNote;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "reconciliation_mode", length = 20)
    private ReconciliationMode reconciliationMode;

    @Column(name = "reconciled_by")
    private UUID reconciledBy;

    @Column(name = "statement_line_id")
    private UUID statementLineId;

    @Column(name = "reversed_at")
    private Instant reversedAt;

    @Column(name = "allocated_amount", nullable = false)
    private Money allocatedAmount;

    @Column(name = "evidence_key", length = 500, updatable = false)
    private String evidenceKey;

    @Column(name = "evidence_name", length = 255, updatable = false)
    private String evidenceName;

    @Column(name = "evidence_content_type", length = 100, updatable = false)
    private String evidenceContentType;

    @Column(name = "evidence_size", updatable = false)
    private Long evidenceSize;

    @Column(name = "evidence_sha256", length = 64, updatable = false)
    private String evidenceSha256;

    protected Payment() {
    }

    private Payment(PaymentSource source, PaymentMethod method, UUID creditorId, UUID debtorId, Money amount,
                    Instant paidAt) {
        Guard.require(method != null, "payment.method_required", "Meio de pagamento obrigatório");
        Guard.require(creditorId != null, "payment.creditor_required", "Credor obrigatório");
        Guard.require(debtorId != null, "payment.debtor_required", "Devedor obrigatório");
        Guard.require(amount != null && amount.isPositive(), "payment.amount_invalid", "Valor do pagamento deve ser positivo");
        Guard.require(paidAt != null, "payment.paid_at_required", "Data do pagamento obrigatória");
        this.source = source;
        this.method = method;
        this.creditorId = creditorId;
        this.debtorId = debtorId;
        this.amount = amount;
        this.paidAt = paidAt;
        this.allocatedAmount = Money.ZERO;
    }

    /**
     * Pagamento integrado já confirmado pelo provedor (consulta ao PSP) ou pelo arquivo de retorno.
     *
     * @param providerTransactionId identificador da transação no provedor ({@code endToEndId}); nulo
     *                              quando o arquivo de retorno não o informa
     */
    public static Payment fromProvider(PaymentMethod method, UUID creditorId, UUID debtorId, Money amount, Instant paidAt,
                                       UUID instructionId, String providerTransactionId, ReviewMode mode, Instant now) {
        Guard.require(mode == ReviewMode.PROVIDER || mode == ReviewMode.STATEMENT, "payment.review_mode_invalid",
                "Pagamento integrado é confirmado pelo provedor ou pelo extrato");
        Payment p = new Payment(PaymentSource.PROVIDER, method, creditorId, debtorId, amount, paidAt);
        p.instructionId = instructionId;
        p.providerTransactionId = providerTransactionId;
        p.reviewRequired = false;
        p.reviewMode = mode;
        p.status = PaymentStatus.CONFIRMED;
        p.confirmedAt = now;
        return p;
    }

    /**
     * Pagamento externo informado por um operador. Acima do limite de risco ({@code reviewRequired})
     * fica {@code INFORMED} até a aprovação de outra pessoa; até o limite é confirmado na hora.
     */
    public static Payment informed(PaymentMethod method, UUID creditorId, UUID debtorId, Money amount, Instant paidAt,
                                   ExternalOrigin origin, String externalReference, String note, Target target,
                                   Evidence evidence, UUID informedBy, boolean reviewRequired, Instant now) {
        Guard.require(origin != null, "payment.origin_required", "Fonte do pagamento obrigatória");
        Guard.require(evidence != null && evidence.sha256() != null, "payment.evidence_required",
                "Evidência do pagamento obrigatória");
        Guard.require(informedBy != null, "payment.informer_required", "Responsável pelo registro obrigatório");
        Guard.require(!paidAt.isAfter(now), "payment.paid_at_future", "Data do pagamento no futuro");
        Guard.require(note == null || note.length() <= NOTE_MAX, "payment.note_too_long", "Observação até 1000 caracteres");
        Guard.require(externalReference == null || externalReference.length() <= REFERENCE_MAX,
                "payment.reference_too_long", "Referência até 100 caracteres");
        Payment p = new Payment(PaymentSource.EXTERNAL, method, creditorId, debtorId, amount, paidAt);
        p.externalOrigin = origin;
        p.externalReference = blankToNull(externalReference);
        p.note = blankToNull(note);
        if (target != null) {
            p.targetKind = target.kind();
            p.targetAgreementId = target.agreementId();
            p.targetItems = target.itemsJson();
            p.targetStrategy = target.strategy();
        }
        p.evidenceKey = evidence.key();
        p.evidenceName = evidence.name();
        p.evidenceContentType = evidence.contentType();
        p.evidenceSize = evidence.size();
        p.evidenceSha256 = evidence.sha256();
        p.informedBy = informedBy;
        p.informedAt = now;
        p.reviewRequired = reviewRequired;
        if (reviewRequired) {
            p.status = PaymentStatus.INFORMED;
            p.reviewMode = ReviewMode.DUAL_CONTROL;
        } else {
            p.status = PaymentStatus.CONFIRMED;
            p.reviewMode = ReviewMode.AUTO_LOW_RISK;
            p.confirmedAt = now;
        }
        return p;
    }

    /** Aprovação do pagamento informado: segregação — quem informou não aprova. */
    public void approve(UUID reviewer, String reviewNote, Instant now) {
        requireReview(reviewer, reviewNote);
        this.status = PaymentStatus.CONFIRMED;
        this.reviewedBy = reviewer;
        this.reviewedAt = now;
        this.reviewNote = blankToNull(reviewNote);
        this.confirmedAt = now;
    }

    public void reject(UUID reviewer, String reviewNote, Instant now) {
        requireReview(reviewer, reviewNote);
        Guard.require(reviewNote != null && !reviewNote.isBlank(), "payment.rejection_reason_required",
                "Informe o motivo da recusa");
        this.status = PaymentStatus.REJECTED;
        this.reviewedBy = reviewer;
        this.reviewedAt = now;
        this.reviewNote = reviewNote.trim();
    }

    private void requireReview(UUID reviewer, String reviewNote) {
        Guard.requireState(status == PaymentStatus.INFORMED, "payment.not_pending_review",
                "Pagamento não está aguardando revisão");
        Guard.require(reviewer != null, "payment.reviewer_required", "Revisor obrigatório");
        if (Objects.equals(reviewer, informedBy)) {
            throw new BusinessRuleException("payment.self_review",
                    "Quem informou o pagamento não pode revisá-lo");
        }
        Guard.require(reviewNote == null || reviewNote.length() <= NOTE_MAX, "payment.note_too_long",
                "Observação até 1000 caracteres");
    }

    /**
     * Conciliação com a fonte autoritativa (PSP, extrato ou análise manual).
     *
     * @return falso se já estava conciliado
     */
    public boolean reconcile(ReconciliationMode mode, UUID by, UUID statementLineId, Instant now) {
        if (status == PaymentStatus.RECONCILED) {
            return false;
        }
        Guard.requireState(status == PaymentStatus.CONFIRMED, "payment.not_confirmed",
                "Somente pagamento confirmado pode ser conciliado");
        if (mode == ReconciliationMode.MANUAL && source == PaymentSource.EXTERNAL && Objects.equals(by, informedBy)) {
            throw new BusinessRuleException("payment.self_reconciliation",
                    "Quem informou o pagamento não pode conciliá-lo manualmente");
        }
        this.status = PaymentStatus.RECONCILED;
        this.reconciliationMode = mode;
        this.reconciledBy = by;
        this.statementLineId = statementLineId;
        this.reconciledAt = now;
        return true;
    }

    /** Estorno total: o pagamento permanece, como {@code REVERSED}; as alocações são revertidas pelo serviço. */
    public PaymentStatus reverse(Instant now) {
        Guard.requireState(status.isEffective(), "payment.not_reversible",
                "Somente pagamento confirmado ou conciliado pode ser estornado");
        PaymentStatus previous = status;
        this.status = PaymentStatus.REVERSED;
        this.reversedAt = now;
        return previous;
    }

    /** Vincula o identificador do provedor a um pagamento criado pelo arquivo de retorno sem ele. */
    public boolean attachProviderTransaction(String transactionId) {
        if (transactionId == null || transactionId.equals(providerTransactionId)) {
            return false;
        }
        Guard.requireState(providerTransactionId == null, "payment.provider_transaction_conflict",
                "Pagamento já vinculado a outra transação do provedor");
        this.providerTransactionId = transactionId;
        return true;
    }

    /** Uma alocação passou a valer (soma ao alocado). */
    public void allocate(Money value) {
        Guard.require(value != null && value.isPositive(), "payment.allocation_invalid", "Valor alocado inválido");
        Guard.requireState(status.isEffective(), "payment.not_confirmed", "Somente pagamento confirmado é alocado");
        Money next = allocatedAmount.plus(value);
        Guard.requireState(!next.isGreaterThan(amount), "payment.over_allocated", "Alocação acima do valor do pagamento");
        this.allocatedAmount = next;
    }

    /** Parte de uma alocação liberada para realocação (volta ao saldo não alocado). */
    public void release(Money value) {
        Guard.require(value != null && value.isPositive() && !value.isGreaterThan(allocatedAmount),
                "payment.release_invalid", "Valor liberado inválido");
        this.allocatedAmount = allocatedAmount.minus(value);
    }

    public Money unallocated() {
        return amount.minus(allocatedAmount);
    }

    public boolean isEffective() {
        return status.isEffective();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Destino solicitado para um pagamento externo.
     *
     * @param itemsJson parcelas (acordo) ou títulos, em ordem de preferência, como JSON (lista de ids)
     */
    public record Target(TargetKind kind, UUID agreementId, String itemsJson, TitleStrategy strategy) {
    }

    /** Arquivo de evidência já gravado no {@code FileStorage}. */
    public record Evidence(String key, String name, String contentType, long size, String sha256) {
    }

    public PaymentSource getSource() {
        return source;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public Money getAmount() {
        return amount;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public UUID getInstructionId() {
        return instructionId;
    }

    public String getProviderTransactionId() {
        return providerTransactionId;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public ExternalOrigin getExternalOrigin() {
        return externalOrigin;
    }

    public TargetKind getTargetKind() {
        return targetKind;
    }

    public UUID getTargetAgreementId() {
        return targetAgreementId;
    }

    public String getTargetItems() {
        return targetItems;
    }

    public TitleStrategy getTargetStrategy() {
        return targetStrategy;
    }

    public String getNote() {
        return note;
    }

    public UUID getInformedBy() {
        return informedBy;
    }

    public Instant getInformedAt() {
        return informedAt;
    }

    public boolean isReviewRequired() {
        return reviewRequired;
    }

    public ReviewMode getReviewMode() {
        return reviewMode;
    }

    public UUID getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getReconciledAt() {
        return reconciledAt;
    }

    public ReconciliationMode getReconciliationMode() {
        return reconciliationMode;
    }

    public UUID getReconciledBy() {
        return reconciledBy;
    }

    public UUID getStatementLineId() {
        return statementLineId;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }

    public Money getAllocatedAmount() {
        return allocatedAmount;
    }

    public String getEvidenceKey() {
        return evidenceKey;
    }

    public String getEvidenceName() {
        return evidenceName;
    }

    public String getEvidenceContentType() {
        return evidenceContentType;
    }

    public Long getEvidenceSize() {
        return evidenceSize;
    }

    public String getEvidenceSha256() {
        return evidenceSha256;
    }
}
