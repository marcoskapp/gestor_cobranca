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
import java.util.function.UnaryOperator;

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
    /**
     * Prefixo da referência depois da anonimização do titular (SEG-20261124-H04, decisão 1463); o resto é o HMAC do
     * texto, calculado fora do domínio. Reservado: recusado na entrada.
     */
    public static final String ANONYMIZED_REFERENCE_PREFIX = "anon:";

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

    /** Muda só na anonimização do titular ({@link #anonymize}), que a troca pela forma anonimizada. */
    @Column(name = "external_reference", length = 100)
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
    @Column(name = "note_encrypted", length = 4000)
    private String note;

    @Column(name = "informed_by", updatable = false)
    private UUID informedBy;

    @Column(name = "informed_at", updatable = false)
    private Instant informedAt;

    @Column(name = "review_required", nullable = false, updatable = false)
    private boolean reviewRequired;

    /** Por que foi para aprovação: AMOUNT, DESTINATION_CUMULATIVE ou INFORMER_CUMULATIVE (nulo sem revisão ou antigo). */
    @Column(name = "review_reason", length = 30, updatable = false)
    private String reviewReason;

    @Column(name = "review_limit", updatable = false)
    private Money reviewLimit;

    @Column(name = "review_considered_amount", updatable = false)
    private Money reviewConsideredAmount;

    /**
     * Canal do informe: {@code CREDITOR_PORTAL} quando a própria pessoa do credor informou no portal;
     * {@code INTEGRATION} quando a integração do credor (token da API pública) informou (nulo nos demais).
     */
    @Column(name = "informed_channel", length = 20, updatable = false)
    private String informedChannel;

    /** Números dos títulos que a pessoa do credor informou (como digitados, separados por vírgula). */
    @Column(name = "creditor_title_numbers", length = 2000, updatable = false)
    private String creditorTitleNumbers;

    /** Verificação do comprovante antes de gravar: CLEAN ou NOT_SCANNED (verificação desligada). */
    @Column(name = "evidence_scan_verdict", length = 20, updatable = false)
    private String evidenceScanVerdict;

    @Column(name = "evidence_scan_engine", length = 40, updatable = false)
    private String evidenceScanEngine;

    @Column(name = "evidence_scanned_at", updatable = false)
    private Instant evidenceScannedAt;

    /** Motivo codificado da recusa visível ao credor ({@link CreditorRejectionReason}); a nota da revisão é interna. */
    @Column(name = "creditor_rejection_reason", length = 30)
    private String creditorRejectionReason;

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

    /** Muda só na anonimização do titular ({@link #anonymize}), que apaga o arquivo e mantém o SHA-256. */
    @Column(name = "evidence_key", length = 500)
    private String evidenceKey;

    @Column(name = "evidence_name", length = 255)
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

    /** Motivo da revisão do pagamento criado por linha de extrato enviada por uma pessoa (sem limite: sempre revisado). */
    public static final String REVIEW_REASON_STATEMENT_LINE = "STATEMENT_LINE";

    /**
     * Pagamento de uma instrução citada numa linha de extrato enviada por uma pessoa (SEG-20261124-C01): o arquivo não
     * prova que o dinheiro entrou, então o pagamento nasce {@code INFORMED} e só é confirmado (alocado, instrução paga)
     * quando outra pessoa aprova — quem importou o extrato é quem "informou". O destino é o da instrução; o identificador
     * da transação da linha só é vinculado na aprovação.
     */
    public static Payment fromStatementPendingReview(PaymentMethod method, UUID creditorId, UUID debtorId, Money amount,
                                                     Instant paidAt, UUID instructionId, Target target, UUID importedBy,
                                                     UUID statementLineId, Instant now) {
        Guard.require(instructionId != null, "payment.instruction_required", "Instrução obrigatória");
        Guard.require(target != null, "payment.target_required", "Destino obrigatório");
        Guard.require(importedBy != null, "payment.informer_required", "Responsável pelo registro obrigatório");
        Guard.require(statementLineId != null, "payment.statement_line_required", "Linha do extrato obrigatória");
        Payment p = new Payment(PaymentSource.PROVIDER, method, creditorId, debtorId, amount, paidAt);
        // sem o identificador da transação até a aprovação (StatementLineReview#approved): gravado aqui, faria a
        // confirmação real do PSP ser tratada como "já conhecida" e, recusado, bloquearia o Pix verdadeiro
        p.instructionId = instructionId;
        p.targetKind = target.kind();
        p.targetAgreementId = target.agreementId();
        p.targetItems = target.itemsJson();
        p.targetStrategy = target.strategy();
        p.informedBy = importedBy;
        p.informedAt = now;
        p.reviewRequired = true;
        p.reviewReason = REVIEW_REASON_STATEMENT_LINE;
        p.reviewMode = ReviewMode.DUAL_CONTROL;
        p.status = PaymentStatus.INFORMED;
        p.statementLineId = statementLineId;
        return p;
    }

    /** Criado por linha de extrato e aguardando (ou já tendo passado pela) revisão de outra pessoa. */
    public boolean isFromStatementReview() {
        return REVIEW_REASON_STATEMENT_LINE.equals(reviewReason);
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
        String reference = blankToNull(externalReference);
        Guard.require(reference == null || !reference.startsWith(ANONYMIZED_REFERENCE_PREFIX),
                "payment.reference_reserved", "A referência não pode começar com \"" + ANONYMIZED_REFERENCE_PREFIX + "\"");
        Payment p = new Payment(PaymentSource.EXTERNAL, method, creditorId, debtorId, amount, paidAt);
        p.externalOrigin = origin;
        p.externalReference = reference;
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

    public static final String CHANNEL_CREDITOR_PORTAL = "CREDITOR_PORTAL";
    public static final String REVIEW_REASON_CREDITOR_PORTAL = "CREDITOR_PORTAL";

    /**
     * Pagamento recebido diretamente pelo credor e informado pela própria pessoa do credor no portal (RS-33): sempre
     * {@code INFORMED} com revisão de outra pessoa da assessoria — nunca confirmado na hora, qualquer que seja o valor.
     */
    public static Payment informedByCreditor(PaymentMethod method, UUID creditorId, UUID debtorId, Money amount,
                                             Instant paidAt, String reference, String note, Target target,
                                             Evidence evidence, String titleNumbers, String scanVerdict,
                                             String scanEngine, Instant scannedAt, UUID creditorPerson, Instant now) {
        Guard.require(titleNumbers != null && !titleNumbers.isBlank() && titleNumbers.length() <= 2000,
                "payment.creditor_titles_required", "Informe os títulos pagos");
        Guard.require("CLEAN".equals(scanVerdict) || "NOT_SCANNED".equals(scanVerdict), "payment.evidence_not_verified",
                "Comprovante sem verificação");
        Guard.require(scannedAt != null, "payment.evidence_not_verified", "Comprovante sem verificação");
        Payment p = informed(method, creditorId, debtorId, amount, paidAt, ExternalOrigin.CREDITOR_DIRECT, reference, note,
                target, evidence, creditorPerson, true, now);
        p.informedChannel = CHANNEL_CREDITOR_PORTAL;
        p.reviewReason = REVIEW_REASON_CREDITOR_PORTAL;
        p.creditorTitleNumbers = titleNumbers;
        p.evidenceScanVerdict = scanVerdict;
        p.evidenceScanEngine = scanEngine;
        p.evidenceScannedAt = scannedAt;
        return p;
    }

    public boolean isFromCreditorPortal() {
        return CHANNEL_CREDITOR_PORTAL.equals(informedChannel);
    }

    public static final String CHANNEL_INTEGRATION = "INTEGRATION";

    /**
     * Registro feito pela integração do credor (API pública, token de integração — H07, decisão 1466): só o
     * pagamento recebido diretamente pelo credor; o canal vem do tipo do ator, nunca do pedido. Não muda a revisão
     * (os limites valem como para os demais, com o teto acumulado por credor).
     */
    public void informedByIntegration() {
        Guard.requireState(informedChannel == null && source == PaymentSource.EXTERNAL, "payment.channel_invalid",
                "Canal do registro inválido");
        Guard.require(externalOrigin == ExternalOrigin.CREDITOR_DIRECT, "payment.integration_origin_invalid",
                "A integração do credor só informa pagamento recebido diretamente pelo credor");
        informedChannel = CHANNEL_INTEGRATION;
    }

    public boolean isFromIntegration() {
        return CHANNEL_INTEGRATION.equals(informedChannel);
    }

    /**
     * Recusa do informe do portal do credor: além da nota interna, o motivo codificado que o credor vê (a nota nunca
     * vai ao credor — pode citar o devedor ou observações da assessoria).
     */
    public void rejectForCreditor(UUID reviewer, String reviewNote, CreditorRejectionReason reason, Instant now) {
        Guard.requireState(isFromCreditorPortal(), "payment.not_from_creditor_portal",
                "Pagamento não foi informado pelo credor no portal");
        Guard.require(reason != null, "payment.creditor_rejection_reason_required",
                "Escolha o motivo que o credor vai ver");
        rejectInternal(reviewer, reviewNote, now);
        this.creditorRejectionReason = reason.name();
    }

    /**
     * Registra por que o pagamento informado foi para a aprovação de outra pessoa (antes de gravar): o limite
     * ultrapassado e o valor comparado com ele, para quem decide ver quanto passa do limite.
     */
    public void reviewTriggeredBy(String reason, Money limit, Money considered) {
        Guard.require(reviewRequired && informedAt != null, "payment.review_not_required",
                "O pagamento não exige aprovação");
        Guard.require(reason != null && limit != null && considered != null, "payment.review_trigger_required",
                "Motivo, limite e valor considerado obrigatórios");
        this.reviewReason = reason;
        this.reviewLimit = limit;
        this.reviewConsideredAmount = considered;
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
        Guard.require(!isFromCreditorPortal(), "payment.creditor_rejection_reason_required",
                "Escolha o motivo que o credor vai ver");
        rejectInternal(reviewer, reviewNote, now);
    }

    private void rejectInternal(UUID reviewer, String reviewNote, Instant now) {
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
        if (!reconcilableBy(mode, by)) {
            throw new BusinessRuleException("payment.self_reconciliation",
                    "Quem informou o pagamento não pode conciliá-lo");
        }
        this.status = PaymentStatus.RECONCILED;
        this.reconciliationMode = mode;
        this.reconciledBy = by;
        this.statementLineId = statementLineId;
        this.reconciledAt = now;
        return true;
    }

    /**
     * Segregação na conciliação: quem informou o pagamento (externo, ou quem importou o extrato do crédito em revisão)
     * não o concilia, nem manualmente nem com um extrato que ele mesmo enviou (SEG-20261124-C01) — a conferência com o
     * banco precisa vir de outra pessoa ou do provedor. Vale para toda origem com pessoa responsável.
     */
    public boolean reconcilableBy(ReconciliationMode mode, UUID by) {
        boolean byPerson = mode == ReconciliationMode.MANUAL || mode == ReconciliationMode.STATEMENT;
        return !(byPerson && informedBy != null && Objects.equals(by, informedBy));
    }

    /**
     * LGPD (SEG-20261124-D01): titular anonimizado — o comprovante (nome e CPF do pagador) e os textos livres saem; ficam
     * o SHA-256 do comprovante (prova de que existiu e de qual era), valores, datas, situação e quem informou e revisou.
     * A referência informada (texto livre, pode citar o pagador — SEG-20261124-H04) vira {@code anon:} + HMAC do texto
     * ({@code referenceDigest}, com a chave de blind index e a assessoria): o extrato continua reconhecendo o pagamento
     * já baixado, sem guardar o texto nem permitir revertê-lo por força bruta.
     *
     * @param referenceDigest HMAC (hex) da referência no escopo da assessoria
     * @return chave do arquivo a apagar do armazenamento depois da transação, ou nula se não havia
     */
    public String anonymize(UnaryOperator<String> referenceDigest) {
        String key = evidenceKey;
        this.evidenceKey = null;
        this.evidenceName = null;
        this.note = null;
        this.reviewNote = null;
        if (externalReference != null && !externalReference.startsWith(ANONYMIZED_REFERENCE_PREFIX)) {
            this.externalReference = ANONYMIZED_REFERENCE_PREFIX + referenceDigest.apply(externalReference);
        }
        return key;
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

    /** Referência informada; nula depois da anonimização do titular (a forma anonimizada não é exibida). */
    public String getExternalReference() {
        return externalReference == null || externalReference.startsWith(ANONYMIZED_REFERENCE_PREFIX)
                ? null : externalReference;
    }

    /** Há referência informada, mesmo que já anonimizada. */
    public boolean hasExternalReference() {
        return externalReference != null;
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

    public String getReviewReason() {
        return reviewReason;
    }

    public String getInformedChannel() {
        return informedChannel;
    }

    public String getCreditorTitleNumbers() {
        return creditorTitleNumbers;
    }

    public String getEvidenceScanVerdict() {
        return evidenceScanVerdict;
    }

    public String getEvidenceScanEngine() {
        return evidenceScanEngine;
    }

    public Instant getEvidenceScannedAt() {
        return evidenceScannedAt;
    }

    public String getCreditorRejectionReason() {
        return creditorRejectionReason;
    }

    public Money getReviewLimit() {
        return reviewLimit;
    }

    public Money getReviewConsideredAmount() {
        return reviewConsideredAmount;
    }
}
